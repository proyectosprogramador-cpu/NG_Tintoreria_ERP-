package com.ngtintoreria.erp.costing;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class IndustrialCostService {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public IndustrialCostService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record SettingsRequest(BigDecimal waterCostPerM3, BigDecimal energyCostPerKwh,
                                  BigDecimal laborCostPerHour, BigDecimal defaultMachineCostPerHour,
                                  BigDecimal overheadPct, BigDecimal usdToPenRate) {}
    public record CalculateRequest(BigDecimal machineHours, BigDecimal laborHours, Integer laborCount,
                                   BigDecimal otherCost, String notes) {}
    private record MaterialCost(UUID itemId,String itemCode,String itemName,BigDecimal plannedQty,BigDecimal actualQty,
                                BigDecimal valuedActualQty,BigDecimal unvaluedActualQty,BigDecimal unitCost,
                                BigDecimal plannedCost,BigDecimal actualCost,String source) {}
    private record CostModel(Map<String,Object> header,List<MaterialCost> materials) {}

    public Map<String,Object> settings() {
        Map<String,Object> s=one("select * from industrial_cost_settings where id=1");
        if(s==null) throw new IllegalStateException("No existe la configuración de costeo industrial");
        return camelMap(s);
    }

    @Transactional
    public Map<String,Object> saveSettings(SettingsRequest input,String actor) {
        if(input==null) throw new IllegalArgumentException("Indique las tarifas de costeo");
        BigDecimal water=nonNegative(input.waterCostPerM3(),"El costo de agua no puede ser negativo");
        BigDecimal energy=nonNegative(input.energyCostPerKwh(),"El costo de energía no puede ser negativo");
        BigDecimal labor=nonNegative(input.laborCostPerHour(),"El costo de mano de obra no puede ser negativo");
        BigDecimal machine=nonNegative(input.defaultMachineCostPerHour(),"El costo de máquina no puede ser negativo");
        BigDecimal overhead=percentage(input.overheadPct(),"El porcentaje de costos indirectos debe estar entre 0 y 100");
        BigDecimal fx=nullablePositive(input.usdToPenRate(),"El tipo de cambio USD/PEN debe ser mayor que cero");
        jdbc.update("""
            update industrial_cost_settings set water_cost_per_m3=?,energy_cost_per_kwh=?,labor_cost_per_hour=?,
                   default_machine_cost_per_hour=?,overhead_pct=?,usd_to_pen_rate=?,updated_by=?,updated_at=now() where id=1
            """,water,energy,labor,machine,overhead,fx,actor);
        audit.record("COSTING_SETTINGS","Costeo",null,"Tarifas industriales actualizadas por "+actor);
        return settings();
    }

    public Map<String,Object> summary() {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("productionOrders",nz(jdbc.queryForObject("select count(*) from production_orders where status not in ('PLANNED','IN_PROCESS','PAUSED','CANCELLED')",Integer.class)));
        out.put("costedOrders",nz(jdbc.queryForObject("select count(*) from production_cost_snapshots where current_snapshot=true",Integer.class)));
        out.put("pendingCosting",nz(jdbc.queryForObject("""
            select count(*) from production_orders po where po.status not in ('PLANNED','IN_PROCESS','PAUSED','CANCELLED')
              and not exists(select 1 from production_cost_snapshots pcs where pcs.production_order_id=po.id and pcs.current_snapshot=true)
            """,Integer.class)));
        Map<String,Object> totals=one("""
            select coalesce(sum(total_cost),0) total_cost,coalesce(sum(allocated_sale_amount_base),0) total_sale,
                   coalesce(sum(gross_margin),0) gross_margin,
                   case when sum(allocated_sale_amount_base)>0 then round((sum(gross_margin)/sum(allocated_sale_amount_base)*100)::numeric,2) else null end margin_pct,
                   coalesce(sum(unvalued_material_qty),0) unvalued_material_qty
              from production_cost_snapshots where current_snapshot=true
            """);
        if(totals!=null) out.putAll(camelMap(totals));
        return out;
    }

    public List<Map<String,Object>> orders() {
        return jdbc.queryForList("""
            select po.id,po.production_number,po.batch_number,po.status,po.batch_weight_kg,po.actual_output_weight_kg,
                   po.waste_weight_kg,po.reprocess_weight_kg,po.water_consumption_l,po.energy_kwh,
                   c.name client_name,so.order_number,so.currency sale_currency,soi.line_no,soi.unit_price,soi.line_total,
                   m.code machine_code,m.name machine_name,m.cost_per_hour,
                   pcs.snapshot_number,pcs.revision_no,pcs.total_cost,pcs.cost_per_output_kg,pcs.allocated_sale_amount_base,
                   pcs.gross_margin,pcs.margin_pct,pcs.unvalued_material_qty,pcs.calculated_at,pcs.calculated_by
              from production_orders po join clients c on c.id=po.client_id
              join sales_orders so on so.id=po.sales_order_id join sales_order_items soi on soi.id=po.sales_order_item_id
              join machines m on m.id=po.machine_id
              left join production_cost_snapshots pcs on pcs.production_order_id=po.id and pcs.current_snapshot=true
             where po.status<>'CANCELLED'
             order by coalesce(pcs.calculated_at,po.updated_at) desc,po.production_number desc
            """).stream().map(IndustrialCostService::camelMap).toList();
    }

    public Map<String,Object> detail(UUID orderId) {
        Map<String,Object> out=new LinkedHashMap<>();
        Map<String,Object> base=orderHeader(orderId,false);
        if(base==null) throw new IllegalArgumentException("La orden de producción no existe");
        out.put("order",camelMap(base));
        CostModel estimate=buildCostModel(orderId,null,false);
        out.put("estimate",camelMap(estimate.header()));
        out.put("estimateMaterials",materialMaps(estimate.materials()));
        Map<String,Object> current=one("select * from production_cost_snapshots where production_order_id=? and current_snapshot=true",orderId);
        if(current!=null){
            out.put("current",camelMap(current));
            out.put("materials",jdbc.queryForList("select * from production_cost_materials where production_cost_snapshot_id=? order by item_name",current.get("id")).stream().map(IndustrialCostService::camelMap).toList());
        }else{
            out.put("current",Map.of());out.put("materials",List.of());
        }
        out.put("history",jdbc.queryForList("select id,snapshot_number,revision_no,total_cost,allocated_sale_amount_base,gross_margin,margin_pct,unvalued_material_qty,calculated_by,calculated_at from production_cost_snapshots where production_order_id=? order by revision_no desc",orderId).stream().map(IndustrialCostService::camelMap).toList());
        return out;
    }

    @Transactional
    public Map<String,Object> calculate(UUID orderId,CalculateRequest input,String actor) {
        Map<String,Object> order=orderHeader(orderId,true);
        if(order==null) throw new IllegalArgumentException("La orden de producción no existe");
        String status=String.valueOf(order.get("status"));
        if(Set.of("PLANNED","IN_PROCESS","PAUSED","CANCELLED").contains(status))
            throw new IllegalArgumentException("El costo real se consolida después de terminar producción. Estado actual: "+status);
        CostModel model=buildCostModel(orderId,input,true);
        Map<String,Object> h=model.header();
        Integer revision=jdbc.queryForObject("select coalesce(max(revision_no),0)+1 from production_cost_snapshots where production_order_id=?",Integer.class,orderId);
        Long seq=jdbc.queryForObject("select nextval('production_cost_snapshot_seq')",Long.class);
        String number=String.format(Locale.ROOT,"CST-%06d",seq==null?1:seq);
        jdbc.update("update production_cost_snapshots set current_snapshot=false where production_order_id=? and current_snapshot=true",orderId);
        UUID id=jdbc.queryForObject("""
            insert into production_cost_snapshots(
              snapshot_number,production_order_id,revision_no,current_snapshot,cost_status,base_currency,sale_currency,exchange_rate,
              allocated_sale_amount_source,allocated_sale_amount_base,batch_weight_kg,actual_output_weight_kg,waste_weight_kg,reprocess_weight_kg,
              material_planned_cost,material_actual_cost,water_quantity_l,water_rate_per_m3,water_cost,
              energy_quantity_kwh,energy_rate_per_kwh,energy_cost,machine_hours,machine_rate_per_hour,machine_cost,
              labor_hours,labor_count,labor_rate_per_hour,labor_cost,direct_cost,overhead_pct,overhead_cost,other_cost,total_cost,
              cost_per_output_kg,gross_margin,margin_pct,unvalued_material_qty,costing_notes,calculated_by,calculated_at)
            values (?,?,?,true,'CALCULATED',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now()) returning id
            """,UUID.class,
            number,orderId,revision,h.get("base_currency"),h.get("sale_currency"),h.get("exchange_rate"),
            h.get("allocated_sale_amount_source"),h.get("allocated_sale_amount_base"),h.get("batch_weight_kg"),h.get("actual_output_weight_kg"),h.get("waste_weight_kg"),h.get("reprocess_weight_kg"),
            h.get("material_planned_cost"),h.get("material_actual_cost"),h.get("water_quantity_l"),h.get("water_rate_per_m3"),h.get("water_cost"),
            h.get("energy_quantity_kwh"),h.get("energy_rate_per_kwh"),h.get("energy_cost"),h.get("machine_hours"),h.get("machine_rate_per_hour"),h.get("machine_cost"),
            h.get("labor_hours"),h.get("labor_count"),h.get("labor_rate_per_hour"),h.get("labor_cost"),h.get("direct_cost"),h.get("overhead_pct"),h.get("overhead_cost"),h.get("other_cost"),h.get("total_cost"),
            h.get("cost_per_output_kg"),h.get("gross_margin"),h.get("margin_pct"),h.get("unvalued_material_qty"),h.get("costing_notes"),actor);
        for(MaterialCost m:model.materials()){
            jdbc.update("""
                insert into production_cost_materials(production_cost_snapshot_id,inventory_item_id,item_code,item_name,planned_quantity_kg,
                  actual_quantity_kg,valued_actual_quantity_kg,unvalued_actual_quantity_kg,unit_cost,planned_cost,actual_cost,cost_source)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,m.itemId(),m.itemCode(),m.itemName(),m.plannedQty(),m.actualQty(),m.valuedActualQty(),m.unvaluedActualQty(),m.unitCost(),m.plannedCost(),m.actualCost(),m.source());
        }
        audit.record("COSTING_CALCULATED","Costeo",orderId.toString(),order.get("production_number")+" · "+number+" · costo "+h.get("total_cost")+" PEN");
        return detail(orderId);
    }

    private CostModel buildCostModel(UUID orderId,CalculateRequest input,boolean strict) {
        Map<String,Object> order=orderHeader(orderId,false);
        if(order==null) throw new IllegalArgumentException("La orden de producción no existe");
        Map<String,Object> cfg=one("select * from industrial_cost_settings where id=1");
        if(cfg==null) throw new IllegalStateException("No existe la configuración de costeo");
        String saleCurrency=String.valueOf(order.get("sale_currency"));
        BigDecimal exchangeRate=BigDecimal.ONE;
        boolean exchangeRateConfigured=true;
        if("USD".equalsIgnoreCase(saleCurrency)){
            Object raw=cfg.get("usd_to_pen_rate");
            if(raw==null&&strict) throw new IllegalArgumentException("Configure el tipo de cambio USD/PEN antes de costear una venta en dólares");
            if(raw==null) exchangeRateConfigured=false;
            else exchangeRate=positive(bd(raw),"El tipo de cambio debe ser mayor que cero");
        } else if(!"PEN".equalsIgnoreCase(saleCurrency)) {
            throw new IllegalArgumentException("Moneda de venta no soportada para costeo: "+saleCurrency);
        }
        BigDecimal batch=bd(order.get("batch_weight_kg"));
        BigDecimal totalItemBatch=bd(order.get("total_item_batch_kg"));
        BigDecimal allocation=totalItemBatch.compareTo(BigDecimal.ZERO)>0?batch.divide(totalItemBatch,10,RoundingMode.HALF_UP):BigDecimal.ONE;
        if(allocation.compareTo(BigDecimal.ONE)>0) allocation=BigDecimal.ONE;
        boolean returnReprocess=order.get("source_return_id")!=null;
        BigDecimal saleSource=returnReprocess?BigDecimal.ZERO:bd(order.get("sale_line_total")).multiply(allocation).setScale(4,RoundingMode.HALF_UP);
        BigDecimal saleBase=saleSource.multiply(exchangeRate).setScale(4,RoundingMode.HALF_UP);

        List<MaterialCost> mats=materialCosts(orderId);
        BigDecimal plannedMat=mats.stream().map(MaterialCost::plannedCost).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(4,RoundingMode.HALF_UP);
        BigDecimal actualMat=mats.stream().map(MaterialCost::actualCost).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(4,RoundingMode.HALF_UP);
        BigDecimal unvalued=mats.stream().map(MaterialCost::unvaluedActualQty).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(6,RoundingMode.HALF_UP);

        BigDecimal waterL=bd(order.get("water_consumption_l")).setScale(3,RoundingMode.HALF_UP);
        BigDecimal waterRate=bd(cfg.get("water_cost_per_m3"));
        BigDecimal waterCost=waterL.divide(new BigDecimal("1000"),10,RoundingMode.HALF_UP).multiply(waterRate).setScale(4,RoundingMode.HALF_UP);
        BigDecimal energyKwh=bd(order.get("energy_kwh")).setScale(3,RoundingMode.HALF_UP);
        BigDecimal energyRate=bd(cfg.get("energy_cost_per_kwh"));
        BigDecimal energyCost=energyKwh.multiply(energyRate).setScale(4,RoundingMode.HALF_UP);

        BigDecimal autoHours=automaticHours(orderId);
        BigDecimal machineHours=input!=null&&input.machineHours()!=null?nonNegativeScale(input.machineHours(),"Las horas de máquina no pueden ser negativas",4):autoHours;
        BigDecimal laborHours=input!=null&&input.laborHours()!=null?nonNegativeScale(input.laborHours(),"Las horas de mano de obra no pueden ser negativas",4):autoHours;
        int laborCount=input!=null&&input.laborCount()!=null?input.laborCount():1;
        if(laborCount<=0) throw new IllegalArgumentException("La cantidad de operarios debe ser mayor que cero");
        BigDecimal machineRate=order.get("machine_cost_per_hour")==null?bd(cfg.get("default_machine_cost_per_hour")):bd(order.get("machine_cost_per_hour"));
        BigDecimal machineCost=machineHours.multiply(machineRate).setScale(4,RoundingMode.HALF_UP);
        BigDecimal laborRate=bd(cfg.get("labor_cost_per_hour"));
        BigDecimal laborCost=laborHours.multiply(laborRate).multiply(BigDecimal.valueOf(laborCount)).setScale(4,RoundingMode.HALF_UP);
        BigDecimal manualOther=input==null?BigDecimal.ZERO:nonNegativeScale(input.otherCost(),"Otros costos no pueden ser negativos",4);
        BigDecimal returnRelated=BigDecimal.ZERO;
        if(order.get("source_return_id")!=null){
            BigDecimal x=jdbc.queryForObject("select coalesce(return_transport_cost,0)+coalesce(other_cost,0) from customer_returns where id=?",BigDecimal.class,order.get("source_return_id"));
            returnRelated=x==null?BigDecimal.ZERO:x.setScale(4,RoundingMode.HALF_UP);
        }
        BigDecimal other=manualOther.add(returnRelated).setScale(4,RoundingMode.HALF_UP);
        BigDecimal direct=actualMat.add(waterCost).add(energyCost).add(machineCost).add(laborCost).setScale(4,RoundingMode.HALF_UP);
        BigDecimal overheadPct=bd(cfg.get("overhead_pct"));
        BigDecimal overhead=direct.multiply(overheadPct).divide(ONE_HUNDRED,10,RoundingMode.HALF_UP).setScale(4,RoundingMode.HALF_UP);
        BigDecimal total=direct.add(overhead).add(other).setScale(4,RoundingMode.HALF_UP);
        BigDecimal output=bd(order.get("actual_output_weight_kg")).setScale(3,RoundingMode.HALF_UP);
        BigDecimal costKg=output.compareTo(BigDecimal.ZERO)>0?total.divide(output,6,RoundingMode.HALF_UP):null;
        BigDecimal margin=exchangeRateConfigured?saleBase.subtract(total).setScale(4,RoundingMode.HALF_UP):null;
        BigDecimal marginPct=exchangeRateConfigured&&saleBase.compareTo(BigDecimal.ZERO)>0?margin.multiply(ONE_HUNDRED).divide(saleBase,4,RoundingMode.HALF_UP):null;

        Map<String,Object> h=new LinkedHashMap<>();
        h.put("base_currency","PEN");h.put("sale_currency",saleCurrency);h.put("exchange_rate",exchangeRate);h.put("exchange_rate_configured",exchangeRateConfigured);
        h.put("allocated_sale_amount_source",saleSource);h.put("allocated_sale_amount_base",saleBase);
        h.put("batch_weight_kg",batch);h.put("actual_output_weight_kg",output);h.put("waste_weight_kg",bd(order.get("waste_weight_kg")));h.put("reprocess_weight_kg",bd(order.get("reprocess_weight_kg")));
        h.put("material_planned_cost",plannedMat);h.put("material_actual_cost",actualMat);h.put("water_quantity_l",waterL);h.put("water_rate_per_m3",waterRate);h.put("water_cost",waterCost);
        h.put("energy_quantity_kwh",energyKwh);h.put("energy_rate_per_kwh",energyRate);h.put("energy_cost",energyCost);
        h.put("automatic_hours",autoHours);h.put("machine_hours",machineHours);h.put("machine_rate_per_hour",machineRate);h.put("machine_cost",machineCost);
        h.put("labor_hours",laborHours);h.put("labor_count",laborCount);h.put("labor_rate_per_hour",laborRate);h.put("labor_cost",laborCost);
        h.put("direct_cost",direct);h.put("overhead_pct",overheadPct);h.put("overhead_cost",overhead);h.put("return_related_cost",returnRelated);h.put("other_cost",other);h.put("total_cost",total);
        h.put("cost_per_output_kg",costKg);h.put("gross_margin",margin);h.put("margin_pct",marginPct);h.put("unvalued_material_qty",unvalued);
        h.put("costing_notes",input==null?null:clean(input.notes()));
        return new CostModel(h,mats);
    }

    private List<MaterialCost> materialCosts(UUID orderId) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select pmr.inventory_item_id,coalesce(ii.code,min(pmr.material_code)) item_code,
                   coalesce(ii.name,min(pmr.material_name)) item_name,ii.standard_cost,
                   coalesce(sum(pmr.planned_quantity_kg),0) planned_qty,coalesce(sum(pmr.actual_quantity_kg),0) actual_qty
              from production_material_requirements pmr left join inventory_items ii on ii.id=pmr.inventory_item_id
             where pmr.production_order_id=?
             group by pmr.inventory_item_id,ii.code,ii.name,ii.standard_cost
             order by coalesce(ii.name,min(pmr.material_name))
            """,orderId);
        List<MaterialCost> out=new ArrayList<>();
        for(Map<String,Object> r:rows){
            UUID itemId=(UUID)r.get("inventory_item_id");BigDecimal planned=bd(r.get("planned_qty"));BigDecimal actual=bd(r.get("actual_qty"));
            BigDecimal standard=r.get("standard_cost")==null?null:bd(r.get("standard_cost"));
            BigDecimal issueQty=BigDecimal.ZERO,issueCost=BigDecimal.ZERO,unvaluedIssue=BigDecimal.ZERO;
            if(itemId!=null){
                Map<String,Object> mv=one("""
                    select coalesce(sum(case when m.movement_type='PRODUCTION_ISSUE' then -m.quantity else 0 end),0) issue_qty,
                           coalesce(sum(case when m.movement_type='PRODUCTION_RETURN' then m.quantity else 0 end),0) return_qty,
                           coalesce(sum(case when m.movement_type='PRODUCTION_ISSUE' then (-m.quantity)*coalesce(l.unit_cost,i.standard_cost,0) else 0 end),0) issue_cost,
                           coalesce(sum(case when m.movement_type='PRODUCTION_ISSUE' and coalesce(l.unit_cost,i.standard_cost) is null then -m.quantity else 0 end),0) unvalued_issue_qty
                      from inventory_movements m join inventory_items i on i.id=m.inventory_item_id
                      left join inventory_lots l on l.id=m.inventory_lot_id
                     where m.reference_type='PRODUCTION_ORDER' and m.reference_id=? and m.inventory_item_id=?
                       and m.movement_type in ('PRODUCTION_ISSUE','PRODUCTION_RETURN')
                    """,orderId,itemId);
                if(mv!=null){issueQty=bd(mv.get("issue_qty"));issueCost=bd(mv.get("issue_cost"));unvaluedIssue=bd(mv.get("unvalued_issue_qty"));}
            }
            BigDecimal fallbackRate=null;String fallbackSource="NO_COST";
            if(itemId!=null){
                List<BigDecimal> latest=jdbc.query("""
                    select pri.unit_cost from purchase_receipt_items pri join purchase_receipts pr on pr.id=pri.purchase_receipt_id
                     where pri.inventory_item_id=? and pri.unit_cost is not null order by pr.received_at desc,pri.id desc limit 1
                    """,(rs,n)->rs.getBigDecimal(1),itemId);
                if(!latest.isEmpty()){fallbackRate=latest.get(0);fallbackSource="LAST_PURCHASE";}
            }
            if(fallbackRate==null&&standard!=null){fallbackRate=standard;fallbackSource="STANDARD";}
            if(fallbackRate==null&&itemId!=null){
                List<BigDecimal> avg=jdbc.query("""
                    select case when sum(current_quantity)>0 then sum(current_quantity*unit_cost)/sum(current_quantity) else null end
                      from inventory_lots where inventory_item_id=? and active=true and current_quantity>0 and unit_cost is not null
                    """,(rs,n)->rs.getBigDecimal(1),itemId);
                if(!avg.isEmpty()&&avg.get(0)!=null){fallbackRate=avg.get(0);fallbackSource="STOCK_AVG";}
            }

            BigDecimal rate=BigDecimal.ZERO;String source="NO_COST";BigDecimal unvalued=BigDecimal.ZERO;
            if(issueQty.compareTo(BigDecimal.ZERO)>0){
                BigDecimal knownIssueQty=issueQty.subtract(unvaluedIssue).max(BigDecimal.ZERO);
                if(unvaluedIssue.compareTo(BigDecimal.ZERO)==0 && issueCost.compareTo(BigDecimal.ZERO)>0){
                    rate=issueCost.divide(issueQty,6,RoundingMode.HALF_UP);source="PRODUCTION_FIFO";
                }else if(unvaluedIssue.compareTo(BigDecimal.ZERO)>0 && fallbackRate!=null){
                    BigDecimal totalIssueCost=issueCost.add(unvaluedIssue.multiply(fallbackRate));
                    rate=totalIssueCost.divide(issueQty,6,RoundingMode.HALF_UP);source="FIFO_PLUS_FALLBACK";
                }else if(knownIssueQty.compareTo(BigDecimal.ZERO)>0 && issueCost.compareTo(BigDecimal.ZERO)>0){
                    rate=issueCost.divide(knownIssueQty,6,RoundingMode.HALF_UP);source="PARTIAL_FIFO";
                    if(actual.compareTo(BigDecimal.ZERO)>0)
                        unvalued=actual.multiply(unvaluedIssue).divide(issueQty,6,RoundingMode.HALF_UP).min(actual);
                }else if(fallbackRate!=null){
                    rate=fallbackRate.setScale(6,RoundingMode.HALF_UP);source=fallbackSource;
                }
            }else if(fallbackRate!=null){
                rate=fallbackRate.setScale(6,RoundingMode.HALF_UP);source=fallbackSource;
            }
            if(actual.compareTo(BigDecimal.ZERO)>0 && rate.compareTo(BigDecimal.ZERO)==0) unvalued=actual;
            BigDecimal valued=actual.subtract(unvalued).max(BigDecimal.ZERO).setScale(6,RoundingMode.HALF_UP);
            BigDecimal actualCost=valued.multiply(rate).setScale(4,RoundingMode.HALF_UP);
            BigDecimal plannedCost=planned.multiply(rate).setScale(4,RoundingMode.HALF_UP);
            out.add(new MaterialCost(itemId,(String)r.get("item_code"),String.valueOf(r.get("item_name")),planned.setScale(6,RoundingMode.HALF_UP),actual.setScale(6,RoundingMode.HALF_UP),valued,unvalued,rate,plannedCost,actualCost,source));
        }
        return out;
    }

    private BigDecimal automaticHours(UUID orderId){
        BigDecimal operationMinutes=jdbc.queryForObject("""
            select coalesce(sum(extract(epoch from (actual_end-actual_start))/60),0)
              from production_operations where production_order_id=? and actual_start is not null and actual_end is not null
            """,BigDecimal.class,orderId);
        // La duración real de la operación incluye las paradas entre inicio y fin.
        // No se suma production_incidents.downtime_minutes para evitar duplicar costos.
        BigDecimal minutes=bd(operationMinutes);
        if(minutes.compareTo(BigDecimal.ZERO)==0){
            Map<String,Object> po=one("select actual_start,actual_end from production_orders where id=?",orderId);
            if(po!=null){
                Instant start=toInstant(po.get("actual_start")),end=toInstant(po.get("actual_end"));
                if(start!=null&&end!=null&&!end.isBefore(start))
                    minutes=BigDecimal.valueOf(Duration.between(start,end).toMillis()).divide(new BigDecimal("60000"),6,RoundingMode.HALF_UP);
            }
        }
        return minutes.divide(new BigDecimal("60"),4,RoundingMode.HALF_UP);
    }

    private static Instant toInstant(Object value){
        if(value instanceof Instant i)return i;
        if(value instanceof OffsetDateTime o)return o.toInstant();
        if(value instanceof ZonedDateTime z)return z.toInstant();
        if(value instanceof LocalDateTime l)return l.atZone(ZoneId.systemDefault()).toInstant();
        if(value instanceof Timestamp t)return t.toInstant();
        return null;
    }

    private Map<String,Object> orderHeader(UUID id,boolean lock){
        String sql="""
            select po.*,c.name client_name,so.order_number,so.currency sale_currency,soi.line_no sale_line_no,
                   soi.unit_price sale_unit_price,soi.line_total sale_line_total,soi.billable_quantity sale_billable_quantity,
                   f.name fabric_name,dc.name color_name,pt.name process_name,m.code machine_code,m.name machine_name,m.cost_per_hour machine_cost_per_hour,
                   (select coalesce(sum(po2.batch_weight_kg),0) from production_orders po2 where po2.sales_order_item_id=po.sales_order_item_id and po2.status<>'CANCELLED' and po2.source_return_id is null) total_item_batch_kg
              from production_orders po join clients c on c.id=po.client_id join sales_orders so on so.id=po.sales_order_id
              join sales_order_items soi on soi.id=po.sales_order_item_id join fabrics f on f.id=soi.fabric_id
              join dye_colors dc on dc.id=soi.color_id join process_types pt on pt.id=soi.process_id join machines m on m.id=po.machine_id
             where po.id=?"""+(lock?" for update of po":"");
        return one(sql,id);
    }

    private static List<Map<String,Object>> materialMaps(List<MaterialCost> rows){
        List<Map<String,Object>> out=new ArrayList<>();for(MaterialCost m:rows){Map<String,Object>x=new LinkedHashMap<>();x.put("inventoryItemId",m.itemId());x.put("itemCode",m.itemCode());x.put("itemName",m.itemName());x.put("plannedQuantityKg",m.plannedQty());x.put("actualQuantityKg",m.actualQty());x.put("valuedActualQuantityKg",m.valuedActualQty());x.put("unvaluedActualQuantityKg",m.unvaluedActualQty());x.put("unitCost",m.unitCost());x.put("plannedCost",m.plannedCost());x.put("actualCost",m.actualCost());x.put("costSource",m.source());out.add(x);}return out;
    }

    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private static Map<String,Object> camelMap(Map<String,Object> in){Map<String,Object> out=new LinkedHashMap<>();for(var e:in.entrySet())out.put(camel(e.getKey()),e.getValue());return out;}
    private static String camel(String s){StringBuilder b=new StringBuilder();boolean up=false;for(char c:s.toCharArray()){if(c=='_'){up=true;continue;}b.append(up?Character.toUpperCase(c):c);up=false;}return b.toString();}
    private static BigDecimal bd(Object x){return x==null?BigDecimal.ZERO:(x instanceof BigDecimal b?b:new BigDecimal(x.toString()));}
    private static BigDecimal nonNegative(BigDecimal x,String message){if(x==null)return BigDecimal.ZERO;if(x.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException(message);return x.setScale(6,RoundingMode.HALF_UP);}
    private static BigDecimal nonNegativeScale(BigDecimal x,String message,int scale){if(x==null)return BigDecimal.ZERO;if(x.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException(message);return x.setScale(scale,RoundingMode.HALF_UP);}
    private static BigDecimal nullablePositive(BigDecimal x,String message){if(x==null)return null;if(x.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException(message);return x.setScale(6,RoundingMode.HALF_UP);}
    private static BigDecimal positive(BigDecimal x,String message){if(x==null||x.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException(message);return x;}
    private static BigDecimal percentage(BigDecimal x,String message){BigDecimal v=x==null?BigDecimal.ZERO:x;if(v.compareTo(BigDecimal.ZERO)<0||v.compareTo(ONE_HUNDRED)>0)throw new IllegalArgumentException(message);return v.setScale(4,RoundingMode.HALF_UP);}
    private static String clean(String x){if(x==null)return null;String v=x.trim();return v.isEmpty()?null:v;}
    private static int nz(Integer x){return x==null?0:x;}
}
