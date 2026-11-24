package com.ngtintoreria.erp.dashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class DashboardService {
    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private final JdbcTemplate jdbc;

    public DashboardService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public Map<String,Object> summary(LocalDate from, LocalDate to){
        LocalDate[] p=period(from,to); from=p[0]; to=p[1];
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("period",Map.of("from",from,"to",to,"days",java.time.temporal.ChronoUnit.DAYS.between(from,to)+1));

        List<String> warnings=new ArrayList<>();
        Map<String,Object> orders=safeOne("orders",warnings,"""
            select count(*) filter(where created_at::date between ? and ?) orders_created,
                   count(*) filter(where status not in ('DELIVERED','CANCELLED')) open_orders,
                   count(*) filter(where requested_date < current_date and status not in ('DELIVERED','CANCELLED')) overdue_orders,
                   count(*) filter(where priority='URGENT' and status not in ('DELIVERED','CANCELLED')) urgent_orders
              from sales_orders
            """,from,to);
        Map<String,Object> production=safeOne("production",warnings,"""
            select count(*) filter(where status='IN_PROCESS') in_process,
                   count(*) filter(where status='PAUSED') paused,
                   count(*) filter(where status='PLANNED' and material_status in ('PENDING','SHORTAGE')) material_blocked,
                   count(*) filter(where status='IN_QUALITY') in_quality,
                   count(*) filter(where status='REPROCESS_REQUIRED') reprocess_required,
                   count(*) filter(where status in ('READY_FOR_DISPATCH','PARTIALLY_DISPATCHED')) ready_dispatch,
                   coalesce(sum(actual_output_weight_kg) filter(where actual_end::date between ? and ?),0) produced_kg,
                   coalesce(sum(waste_weight_kg) filter(where actual_end::date between ? and ?),0) waste_kg,
                   coalesce(sum(reprocess_weight_kg) filter(where actual_end::date between ? and ?),0) reprocess_kg,
                   count(*) filter(where actual_end::date between ? and ?) completed_in_period
              from production_orders
            """,from,to,from,to,from,to,from,to);
        Map<String,Object> quality=safeOne("quality",warnings,"""
            select count(*) filter(where decision_at::date between ? and ? and status='DECIDED') decided,
                   count(*) filter(where decision_at::date between ? and ? and result='APPROVED') approved,
                   count(*) filter(where decision_at::date between ? and ? and result='REPROCESS') reprocess,
                   count(*) filter(where decision_at::date between ? and ? and result='REJECTED') rejected,
                   count(*) filter(where decision_at::date between ? and ? and result='OBSERVED') observed
              from quality_inspections
            """,from,to,from,to,from,to,from,to,from,to);
        long decided=lng(quality.get("decided"));
        quality.put("reprocess_rate_pct",decided==0?BigDecimal.ZERO:bd(quality.get("reprocess")).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(decided),2,java.math.RoundingMode.HALF_UP));

        Map<String,Object> dispatch=safeOne("dispatch",warnings,"""
            select count(*) filter(where status='READY') ready,
                   count(*) filter(where status='DISPATCHED') awaiting_delivery,
                   count(*) filter(where delivered_at::date between ? and ? and status='DELIVERED') deliveries,
                   coalesce(sum(total_weight_kg) filter(where delivered_at::date between ? and ? and status='DELIVERED'),0) delivered_kg
              from dispatches
            """,from,to,from,to);

        Map<String,Object> inventory=safeOne("inventory",warnings,"""
            with physical as (select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id),
                 reserved as (select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id),
                 stock as (
                   select i.id,i.minimum_stock,coalesce(p.qty,0) physical,coalesce(r.qty,0) reserved,
                          greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0) available
                     from inventory_items i left join physical p on p.inventory_item_id=i.id left join reserved r on r.inventory_item_id=i.id
                    where i.active=true
                 )
            select count(*) items,
                   count(*) filter(where physical=0) no_stock,
                   count(*) filter(where physical>0 and available<=minimum_stock) low_stock
              from stock
            """);

        Map<String,Object> purchasing=safeOne("purchasing",warnings,"""
            select count(*) filter(where status in ('ISSUED','SENT','PARTIALLY_RECEIVED')) open_purchase_orders,
                   count(*) filter(where expected_date < current_date and status in ('ISSUED','SENT','PARTIALLY_RECEIVED')) overdue_purchase_orders
              from purchase_orders
            """);

        Map<String,Object> kpis=new LinkedHashMap<>();
        kpis.putAll(prefix("orders",orders));
        kpis.putAll(prefix("production",production));
        kpis.putAll(prefix("quality",quality));
        kpis.putAll(prefix("dispatch",dispatch));
        kpis.putAll(prefix("inventory",inventory));
        kpis.putAll(prefix("purchasing",purchasing));
        out.put("kpis",kpis);

        out.put("funnel",List.of(
            stage("Pedidos abiertos",orders.get("open_orders"),"COMMERCIAL"),
            stage("OP en proceso",production.get("in_process"),"PRODUCTION"),
            stage("En calidad",production.get("in_quality"),"QUALITY"),
            stage("Para despacho",production.get("ready_dispatch"),"DISPATCH"),
            stage("En ruta / entregando",dispatch.get("awaiting_delivery"),"DELIVERY")
        ));

        try { out.put("trend",trend(from,to)); }
        catch (DataAccessException ex) { warn("trend",warnings,ex); out.put("trend",List.of()); }
        try { out.put("alerts",alerts()); }
        catch (DataAccessException ex) { warn("alerts",warnings,ex); out.put("alerts",List.of()); }
        out.put("recentActivity",safeList("recentActivity",warnings,"""
            select created_at,actor,module,action,reference_id,detail
              from audit_events order by created_at desc limit 12
            """));
        out.put("warnings",warnings);
        return out;
    }

    public Map<String,Object> financial(LocalDate from, LocalDate to){
        LocalDate[] p=period(from,to); from=p[0]; to=p[1];
        Map<String,Object> totals=one("""
            select count(*) costed_orders,
                   coalesce(sum(pcs.allocated_sale_amount_base),0) sale_pen,
                   coalesce(sum(pcs.total_cost),0) cost_pen,
                   coalesce(sum(pcs.gross_margin),0) margin_pen,
                   coalesce(sum(pcs.unvalued_material_qty),0) unvalued_material_kg
              from production_cost_snapshots pcs
              join production_orders po on po.id=pcs.production_order_id
             where pcs.current_snapshot=true and po.actual_end::date between ? and ?
            """,from,to);
        BigDecimal sale=bd(totals.get("sale_pen")),margin=bd(totals.get("margin_pen"));
        totals.put("margin_pct",sale.signum()==0?BigDecimal.ZERO:margin.multiply(BigDecimal.valueOf(100)).divide(sale,2,java.math.RoundingMode.HALF_UP));
        Map<String,Object> coverage=one("""
            select count(*) filter(where po.actual_end::date between ? and ?) completed_orders,
                   count(*) filter(where po.actual_end::date between ? and ? and pcs.id is not null) costed_completed_orders
              from production_orders po
              left join production_cost_snapshots pcs on pcs.production_order_id=po.id and pcs.current_snapshot=true
            """,from,to,from,to);
        long complete=lng(coverage.get("completed_orders"));
        coverage.put("coverage_pct",complete==0?BigDecimal.ZERO:bd(coverage.get("costed_completed_orders")).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(complete),2,java.math.RoundingMode.HALF_UP));
        List<Map<String,Object>> topClients=jdbc.queryForList("""
            select c.name client_name,count(*) op_count,
                   coalesce(sum(pcs.allocated_sale_amount_base),0) sale_pen,
                   coalesce(sum(pcs.total_cost),0) cost_pen,
                   coalesce(sum(pcs.gross_margin),0) margin_pen,
                   case when coalesce(sum(pcs.allocated_sale_amount_base),0)=0 then 0
                        else round(sum(pcs.gross_margin)*100/sum(pcs.allocated_sale_amount_base),2) end margin_pct
              from production_cost_snapshots pcs join production_orders po on po.id=pcs.production_order_id join clients c on c.id=po.client_id
             where pcs.current_snapshot=true and po.actual_end::date between ? and ?
             group by c.id,c.name order by margin_pen desc limit 8
            """,from,to);
        List<Map<String,Object>> topOrders=jdbc.queryForList("""
            select po.production_number,po.batch_number,c.name client_name,pcs.total_cost,pcs.allocated_sale_amount_base,
                   pcs.gross_margin,pcs.margin_pct,pcs.cost_per_output_kg,pcs.unvalued_material_qty
              from production_cost_snapshots pcs join production_orders po on po.id=pcs.production_order_id join clients c on c.id=po.client_id
             where pcs.current_snapshot=true and po.actual_end::date between ? and ?
             order by pcs.gross_margin desc limit 10
            """,from,to);
        return Map.of("period",Map.of("from",from,"to",to),"totals",totals,"coverage",coverage,"topClients",topClients,"topOrders",topOrders);
    }

    private List<Map<String,Object>> trend(LocalDate from,LocalDate to){
        LocalDate trendFrom=from;
        if(trendFrom.isBefore(to.minusDays(30))) trendFrom=to.minusDays(30);

        // El calendario se genera en Java para evitar la ambigüedad DATE/TIMESTAMP
        // de generate_series con parámetros JDBC en algunas instalaciones.
        Map<LocalDate,Map<String,Object>> days=new LinkedHashMap<>();
        for(LocalDate d=trendFrom; !d.isAfter(to); d=d.plusDays(1)){
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("day",d);
            row.put("produced_kg",BigDecimal.ZERO);
            row.put("delivered_kg",BigDecimal.ZERO);
            row.put("orders_created",0L);
            days.put(d,row);
        }

        mergeTrend(days,jdbc.queryForList("""
            select actual_end::date AS trend_day,coalesce(sum(actual_output_weight_kg),0) produced_kg
              from production_orders
             where actual_end::date between ? and ?
             group by actual_end::date
             order by actual_end::date
            """,trendFrom,to),"produced_kg");

        mergeTrend(days,jdbc.queryForList("""
            select delivered_at::date AS trend_day,coalesce(sum(total_weight_kg),0) delivered_kg
              from dispatches
             where status='DELIVERED' and delivered_at::date between ? and ?
             group by delivered_at::date
             order by delivered_at::date
            """,trendFrom,to),"delivered_kg");

        mergeTrend(days,jdbc.queryForList("""
            select created_at::date AS trend_day,count(*) orders_created
              from sales_orders
             where created_at::date between ? and ?
             group by created_at::date
             order by created_at::date
            """,trendFrom,to),"orders_created");

        return new ArrayList<>(days.values());
    }

    private static void mergeTrend(Map<LocalDate,Map<String,Object>> days,List<Map<String,Object>> rows,String valueKey){
        for(Map<String,Object> row:rows){
            Object rawDay=row.get("trend_day");
            if(rawDay==null) continue;
            LocalDate day;
            if(rawDay instanceof LocalDate ld) day=ld;
            else if(rawDay instanceof java.sql.Date sd) day=sd.toLocalDate();
            else day=LocalDate.parse(rawDay.toString());
            Map<String,Object> target=days.get(day);
            if(target!=null) target.put(valueKey,row.getOrDefault(valueKey,BigDecimal.ZERO));
        }
    }

    private List<Map<String,Object>> alerts(){
        List<Map<String,Object>> a=new ArrayList<>();
        addAlerts(a,jdbc.queryForList("""
            select 'HIGH' severity,'PEDIDO' category,order_number reference,
                   'Pedido vencido' title,c.name||' · Fecha requerida '||requested_date detail,requested_date::timestamp event_at
              from sales_orders so join clients c on c.id=so.client_id
             where requested_date < current_date and so.status not in ('DELIVERED','CANCELLED')
             order by requested_date asc limit 5
            """));
        addAlerts(a,jdbc.queryForList("""
            select case when priority='URGENT' then 'CRITICAL' else 'HIGH' end severity,'PRODUCCION' category,production_number reference,
                   'OP bloqueada por materiales' title,c.name||' · '||batch_number detail,scheduled_start event_at
              from production_orders po join clients c on c.id=po.client_id
             where status='PLANNED' and material_status in ('SHORTAGE','PENDING')
             order by case priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end,scheduled_start limit 5
            """));
        addAlerts(a,jdbc.queryForList("""
            with physical as (select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id),
                 reserved as (select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id)
            select case when coalesce(p.qty,0)=0 then 'CRITICAL' else 'MEDIUM' end severity,'ALMACEN' category,i.code reference,
                   case when coalesce(p.qty,0)=0 then 'Insumo sin stock' else 'Stock bajo' end title,
                   i.name||' · disponible '||greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0)||' '||i.base_unit detail,now() event_at
              from inventory_items i left join physical p on p.inventory_item_id=i.id left join reserved r on r.inventory_item_id=i.id
             where i.active=true and greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0)<=i.minimum_stock
             order by coalesce(p.qty,0),lower(i.name) limit 5
            """));
        addAlerts(a,jdbc.queryForList("""
            select 'HIGH' severity,'COMPRAS' category,order_number reference,'OC con entrega vencida' title,
                   coalesce(s.name,'Proveedor')||' · esperada '||expected_date detail,expected_date::timestamp event_at
              from purchase_orders po left join suppliers s on s.id=po.supplier_id
             where expected_date < current_date and po.status in ('ISSUED','SENT','PARTIALLY_RECEIVED')
             order by expected_date limit 5
            """));
        addAlerts(a,jdbc.queryForList("""
            select case when status='QUALITY_REJECTED' then 'CRITICAL' else 'HIGH' end severity,'CALIDAD' category,production_number reference,
                   case when status='QUALITY_REJECTED' then 'OP rechazada por Calidad' else 'OP requiere reproceso' end title,
                   c.name||' · '||batch_number detail,po.updated_at event_at
              from production_orders po join clients c on c.id=po.client_id
             where po.status in ('REPROCESS_REQUIRED','QUALITY_REJECTED') order by po.updated_at desc limit 5
            """));
        addAlerts(a,jdbc.queryForList("""
            select 'MEDIUM' severity,'DESPACHO' category,dispatch_number reference,'Despacho pendiente de entrega' title,
                   c.name||' · '||round(total_weight_kg,3)||' kg' detail,actual_dispatch_at event_at
              from dispatches d join clients c on c.id=d.client_id
             where d.status='DISPATCHED' order by actual_dispatch_at asc limit 5
            """));
        addAlerts(a,jdbc.queryForList("""
            select case when cr.status in ('RECEIVED','UNDER_REVIEW') then 'HIGH' else 'MEDIUM' end severity,
                   'POSTVENTA' category,cr.return_number reference,'Devolución de cliente pendiente' title,
                   c.name||' · '||round(cr.total_weight_kg,3)||' kg · '||cr.status detail,
                   coalesce(cr.received_at,cr.created_at) event_at
              from customer_returns cr join clients c on c.id=cr.client_id
             where cr.status not in ('CLOSED','CANCELLED')
             order by case when cr.status in ('RECEIVED','UNDER_REVIEW') then 0 else 1 end,coalesce(cr.received_at,cr.created_at) asc limit 5
            """));
        addAlerts(a,jdbc.queryForList("""
            select case when coalesce(m.operational_status,'AVAILABLE')='OUT_OF_SERVICE' then 'CRITICAL' else 'HIGH' end severity,
                   'MANTENIMIENTO' category,m.code reference,
                   case when coalesce(m.operational_status,'AVAILABLE')='OUT_OF_SERVICE' then 'Máquina fuera de servicio' else 'Mantenimiento vencido' end title,
                   m.name||case when m.next_maintenance_at is not null then ' · fecha '||to_char(m.next_maintenance_at,'DD/MM/YYYY') else '' end||
                   case when m.next_maintenance_meter_hours is not null then ' · '||m.current_meter_hours||'/'||m.next_maintenance_meter_hours||' h' else '' end detail,
                   coalesce(m.next_maintenance_at,now()) event_at
              from machines m
             where m.active=true and (coalesce(m.operational_status,'AVAILABLE')='OUT_OF_SERVICE' or
                   (m.next_maintenance_at is not null and m.next_maintenance_at<now()) or
                   (m.next_maintenance_meter_hours is not null and m.current_meter_hours>=m.next_maintenance_meter_hours))
             order by case when coalesce(m.operational_status,'AVAILABLE')='OUT_OF_SERVICE' then 0 else 1 end,event_at limit 5
            """));
        addAlerts(a,jdbc.queryForList("""
            select case when mw.priority='CRITICAL' then 'CRITICAL' when mw.priority='HIGH' then 'HIGH' else 'MEDIUM' end severity,
                   'MANTENIMIENTO' category,mw.maintenance_number reference,'Mantenimiento próximo' title,
                   m.code||' · '||mw.title detail,mw.scheduled_start event_at
              from maintenance_work_orders mw join machines m on m.id=mw.machine_id
             where mw.status='PLANNED' and mw.scheduled_start between now() and now()+interval '24 hours'
             order by mw.scheduled_start limit 5
            """));
        a.sort(Comparator.comparingInt((Map<String,Object>x)->severityRank(String.valueOf(x.get("severity")))).thenComparing(x->String.valueOf(x.getOrDefault("event_at",""))));
        return a.size()>18?a.subList(0,18):a;
    }

    private static int severityRank(String s){return switch(s){case "CRITICAL"->0;case "HIGH"->1;case "MEDIUM"->2;default->3;};}
    private static void addAlerts(List<Map<String,Object>> dest,List<Map<String,Object>> src){dest.addAll(src);}
    private Map<String,Object> safeOne(String section,List<String> warnings,String sql,Object...args){
        try { return one(sql,args); }
        catch (DataAccessException ex) { warn(section,warnings,ex); return new LinkedHashMap<>(); }
    }
    private List<Map<String,Object>> safeList(String section,List<String> warnings,String sql,Object...args){
        try { return jdbc.queryForList(sql,args); }
        catch (DataAccessException ex) { warn(section,warnings,ex); return new ArrayList<>(); }
    }
    private static void warn(String section,List<String> warnings,DataAccessException ex){
        warnings.add(section);
        Throwable root=ex; while(root.getCause()!=null) root=root.getCause();
        log.error("Dashboard section {} failed: {}",section,root.getMessage(),ex);
    }
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> r=jdbc.queryForList(sql,args);return r.isEmpty()?new LinkedHashMap<>():new LinkedHashMap<>(r.get(0));}
    private static Map<String,Object> stage(String name,Object count,String code){return Map.of("name",name,"count",count==null?0:count,"code",code);}
    private static Map<String,Object> prefix(String prefix,Map<String,Object> src){Map<String,Object> r=new LinkedHashMap<>();src.forEach((k,v)->r.put(prefix+"_"+k,v));return r;}
    private static BigDecimal bd(Object v){return v==null?BigDecimal.ZERO:new BigDecimal(v.toString());}
    private static long lng(Object v){return v==null?0L:Long.parseLong(v.toString());}
    private static LocalDate[] period(LocalDate from,LocalDate to){LocalDate t=to==null?LocalDate.now():to;LocalDate f=from==null?t.minusDays(29):from;if(f.isAfter(t)){LocalDate x=f;f=t;t=x;}if(f.isBefore(t.minusDays(365)))f=t.minusDays(365);return new LocalDate[]{f,t};}
}
