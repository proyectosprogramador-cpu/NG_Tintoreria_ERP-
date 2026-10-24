package com.ngtintoreria.erp.copilot;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.inventory.PurchaseRequestService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class CopilotProactiveService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PurchaseRequestService purchaseRequests;
    private final long repeatMinutes;
    private final long dismissHours;

    public CopilotProactiveService(JdbcTemplate jdbc,
                                   AuditService audit,
                                   PurchaseRequestService purchaseRequests,
                                   @Value("${app.copilot.proactive.repeat-minutes:240}") long repeatMinutes,
                                   @Value("${app.copilot.proactive.dismiss-hours:24}") long dismissHours) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.purchaseRequests = purchaseRequests;
        this.repeatMinutes = Math.max(30, repeatMinutes);
        this.dismissHours = Math.max(1, dismissHours);
    }

    public record ProactiveAlert(String key,
                                 String severity,
                                 String category,
                                 String title,
                                 String message,
                                 String detail,
                                 String route,
                                 String reference,
                                 String actionType,
                                 String actionLabel,
                                 Map<String,Object> actionPayload,
                                 OffsetDateTime detectedAt) {}

    public record Briefing(String title,
                           String summary,
                           List<CopilotService.Fact> metrics,
                           List<ProactiveAlert> priorities,
                           OffsetDateTime generatedAt) {}

    public List<ProactiveAlert> activeAlerts(Authentication auth, int limit) {
        int safe = Math.min(Math.max(limit, 1), 50);
        return visibleAlerts(auth, false).stream().limit(safe).toList();
    }

    @Transactional
    public List<ProactiveAlert> dueAlerts(Authentication auth) {
        String email = auth.getName();
        List<ProactiveAlert> visible = visibleAlerts(auth, false);
        Map<String,Map<String,Object>> states = states(email);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(repeatMinutes);
        List<ProactiveAlert> due = new ArrayList<>();
        for (ProactiveAlert a : visible) {
            Map<String,Object> s = states.get(a.key());
            OffsetDateTime last = s == null ? null : asOffsetDateTime(s.get("last_notified_at"));
            if (last == null || last.isBefore(cutoff)) {
                due.add(a);
                if (due.size() >= 6) break;
            }
        }
        for (ProactiveAlert a : due) {
            jdbc.update("""
                insert into copilot_alert_user_state(user_email,alert_key,last_notified_at,last_seen_at,updated_at)
                values (?,?,now(),now(),now())
                on conflict(user_email,alert_key) do update set last_notified_at=now(),last_seen_at=now(),updated_at=now()
                """, email, a.key());
        }
        return due;
    }

    public Briefing briefing(Authentication auth) {
        List<CopilotService.Fact> metrics = new ArrayList<>();
        List<String> sentences = new ArrayList<>();

        if (can(auth,"PRODUCTION_ORDERS_READ")) {
            long active = scalar("select count(*) from production_orders where status in ('PLANNED','IN_PROCESS','PAUSED')");
            long late = scalar("select count(*) from production_orders where scheduled_end<now() and status in ('PLANNED','IN_PROCESS','PAUSED')");
            metrics.add(new CopilotService.Fact("Producción", String.valueOf(active), late>0?late+" fuera de plazo":"Sin OP vencidas", late>0?"warn":"ok", "production"));
            sentences.add(active+" OP activas"+(late>0?" y "+late+" vencidas":""));
        }
        if (can(auth,"QUALITY_READ")) {
            long q = scalar("select count(*) from production_orders where status in ('IN_QUALITY','REPROCESS_REQUIRED','QUALITY_REJECTED')");
            metrics.add(new CopilotService.Fact("Calidad",String.valueOf(q),q>0?"Pendientes de decisión o seguimiento":"Sin pendientes",q>0?"warn":"ok","quality"));
            sentences.add(q+" en calidad");
        }
        if (can(auth,"DISPATCH_READ")) {
            long d = scalar("select count(*) from production_orders where status='READY_FOR_DISPATCH'") + scalar("select count(*) from dispatches where status in ('READY','DISPATCHED')");
            metrics.add(new CopilotService.Fact("Despacho",String.valueOf(d),d>0?"Listos o en ruta":"Sin pendientes",d>0?"info":"ok","dispatch"));
            sentences.add(d+" pendientes de despacho/entrega");
        }
        if (can(auth,"INVENTORY_READ")) {
            long zero = scalar("""
                with physical as (select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id),
                     reserved as (select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id)
                select count(*) from inventory_items i left join physical p on p.inventory_item_id=i.id left join reserved r on r.inventory_item_id=i.id
                where i.active=true and greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0)=0
                """);
            metrics.add(new CopilotService.Fact("Sin stock",String.valueOf(zero),zero>0?"Requiere revisión de abastecimiento":"Sin quiebres",zero>0?"warn":"ok","inventory"));
            sentences.add(zero+" insumos sin stock");
        }
        if (can(auth,"MAINTENANCE_READ")) {
            long unavailable = scalar("select count(*) from machines where active=true and operational_status in ('IN_MAINTENANCE','OUT_OF_SERVICE')");
            long due = scalar("select count(*) from machines where active=true and operational_status<>'RETIRED' and ((next_maintenance_at is not null and next_maintenance_at<=now()+interval '1 day') or (next_maintenance_meter_hours is not null and current_meter_hours>=next_maintenance_meter_hours))");
            metrics.add(new CopilotService.Fact("Máquinas",String.valueOf(unavailable),due+" mantenimientos próximos/vencidos",unavailable>0||due>0?"warn":"ok","maintenance"));
            sentences.add(unavailable+" máquinas no disponibles");
        }

        List<ProactiveAlert> priorities = visibleAlerts(auth,false).stream().limit(5).toList();
        String summary = sentences.isEmpty()
                ? "No hay módulos operativos disponibles para tu rol."
                : "Briefing de turno: "+String.join("; ",sentences)+". "+(priorities.isEmpty()?"No detecto alertas activas que requieran intervención.":"Hay "+priorities.size()+" prioridades visibles para tu rol.");
        return new Briefing("Briefing de turno", summary, metrics, priorities, OffsetDateTime.now());
    }

    @Transactional
    public Map<String,Object> updateState(String userEmail, String key, String action, OffsetDateTime hiddenUntil) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("La alerta no es válida");
        String op = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        OffsetDateTime until;
        String lastAction;
        switch (op) {
            case "SNOOZE" -> {
                until = hiddenUntil == null ? OffsetDateTime.now().plusHours(1) : hiddenUntil;
                if (until.isAfter(OffsetDateTime.now().plusDays(7))) throw new IllegalArgumentException("La alerta no puede posponerse más de 7 días");
                lastAction = "SNOOZE";
            }
            case "DISMISS" -> {
                until = OffsetDateTime.now().plusHours(dismissHours);
                lastAction = "DISMISS";
            }
            case "ACK" -> {
                until = null;
                lastAction = "ACK";
            }
            default -> throw new IllegalArgumentException("Acción de alerta no reconocida");
        }
        jdbc.update("""
            insert into copilot_alert_user_state(user_email,alert_key,hidden_until,last_action,last_seen_at,updated_at)
            values (?,?,?,?,now(),now())
            on conflict(user_email,alert_key) do update set hidden_until=excluded.hidden_until,last_action=excluded.last_action,last_seen_at=now(),updated_at=now()
            """, userEmail, key, until, lastAction);
        audit.record("COPILOT_ALERT_"+lastAction,"NG Copiloto",key,"Alerta "+lastAction+(until==null?"":" hasta "+until));
        return Map.of("ok",true,"key",key,"action",lastAction,"hiddenUntil",until==null?"":until.toString());
    }

    @Transactional
    public Map<String,Object> confirmAction(Authentication auth, String type, Map<String,Object> payload) {
        String action = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if ("CREATE_PURCHASE_REQUEST_FROM_PRODUCTION".equals(action)) {
            if (!can(auth,"PURCHASE_REQUESTS_WRITE")) throw new IllegalArgumentException("No tienes permiso para crear solicitudes de compra");
            Object raw = payload == null ? null : payload.get("productionOrderId");
            if (raw == null) throw new IllegalArgumentException("Falta la orden de producción origen");
            UUID orderId;
            try { orderId = UUID.fromString(String.valueOf(raw)); }
            catch (Exception e) { throw new IllegalArgumentException("La orden de producción origen no es válida"); }
            Map<String,Object> result = purchaseRequests.fromProduction(orderId,auth.getName());
            String number = "Solicitud creada";
            Object header = result.get("header");
            if (header instanceof Map<?,?> h && h.get("request_number") != null) number = String.valueOf(h.get("request_number"));
            audit.record("COPILOT_ACTION_CONFIRMED","NG Copiloto",orderId.toString(),"CREATE_PURCHASE_REQUEST · "+number);
            LinkedHashMap<String,Object> out = new LinkedHashMap<>();
            out.put("ok",true); out.put("message",number+" preparada en borrador. Revísala antes de solicitar aprobación."); out.put("route","inventory"); out.put("result",result);
            return out;
        }
        throw new IllegalArgumentException("La acción propuesta no está habilitada");
    }

    private List<ProactiveAlert> visibleAlerts(Authentication auth, boolean includeHidden) {
        List<ProactiveAlert> raw = detect(auth);
        if (raw.isEmpty()) return raw;
        Map<String,Map<String,Object>> states = states(auth.getName());
        OffsetDateTime now = OffsetDateTime.now();
        List<ProactiveAlert> out = new ArrayList<>();
        for (ProactiveAlert a : raw) {
            Map<String,Object> s = states.get(a.key());
            OffsetDateTime hiddenUntil = s == null ? null : asOffsetDateTime(s.get("hidden_until"));
            if (!includeHidden && hiddenUntil != null && hiddenUntil.isAfter(now)) continue;
            out.add(a);
        }
        out.sort(Comparator.comparingInt((ProactiveAlert a)->severityRank(a.severity())).thenComparing(ProactiveAlert::category).thenComparing(ProactiveAlert::title));
        return out;
    }

    /** Alertas operativas globales para el motor de comunicaciones. No incluye acciones sensibles. */
    public List<ProactiveAlert> systemAlerts() {
        List<ProactiveAlert> out = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        detectInventory(null,out,now);
        detectProduction(out,now);
        detectQuality(out,now);
        detectDispatch(out,now);
        detectMaintenance(out,now);
        detectReturns(out,now);
        out.sort(Comparator.comparingInt((ProactiveAlert a)->severityRank(a.severity())).thenComparing(ProactiveAlert::category).thenComparing(ProactiveAlert::title));
        return out;
    }

    private List<ProactiveAlert> detect(Authentication auth) {
        List<ProactiveAlert> out = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        if (can(auth,"INVENTORY_READ")) detectInventory(auth,out,now);
        if (can(auth,"PRODUCTION_ORDERS_READ")) detectProduction(out,now);
        if (can(auth,"QUALITY_READ")) detectQuality(out,now);
        if (can(auth,"DISPATCH_READ")) detectDispatch(out,now);
        if (can(auth,"MAINTENANCE_READ")) detectMaintenance(out,now);
        if (can(auth,"RETURNS_READ")) detectReturns(out,now);
        return out;
    }

    private void detectInventory(Authentication auth, List<ProactiveAlert> out, OffsetDateTime now) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            with physical as (select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id),
                 reserved as (select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id)
            select i.id,i.code,i.name,i.base_unit,i.minimum_stock,i.safety_stock,
                   greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0) available,
                   shortage.production_order_id,shortage.production_number,shortage.shortage_quantity_kg
              from inventory_items i
              left join physical p on p.inventory_item_id=i.id
              left join reserved r on r.inventory_item_id=i.id
              left join lateral (
                  select po.id production_order_id,po.production_number,pmr.shortage_quantity_kg
                    from production_material_requirements pmr join production_orders po on po.id=pmr.production_order_id
                   where pmr.inventory_item_id=i.id and pmr.shortage_quantity_kg>0 and po.status='PLANNED'
                   order by case po.priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end,po.scheduled_start
                   limit 1
              ) shortage on true
             where i.active=true and greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0)<=i.minimum_stock
             order by greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0),lower(i.name) limit 12
            """);
        for (Map<String,Object> r : rows) {
            BigDecimal available = toBig(r.get("available"));
            boolean empty = available.signum()==0;
            String severity = empty?"CRITICAL":"IMPORTANT";
            Map<String,Object> payload = Map.of();
            String actionType = null, actionLabel = null;
            if (r.get("production_order_id") != null && can(auth,"PURCHASE_REQUESTS_WRITE")) {
                payload = Map.of("productionOrderId",String.valueOf(r.get("production_order_id")));
                actionType = "CREATE_PURCHASE_REQUEST_FROM_PRODUCTION";
                actionLabel = "Preparar solicitud de compra";
            }
            String message = empty ? "El insumo está sin stock disponible." : "El insumo cayó al mínimo configurado.";
            String detail = r.get("name")+" · disponible "+num(r.get("available"))+" "+r.get("base_unit")+" · mínimo "+num(r.get("minimum_stock"));
            if (r.get("production_number") != null) detail += " · faltante asociado a "+r.get("production_number");
            out.add(new ProactiveAlert("inventory:"+r.get("id")+":"+(empty?"zero":"low"),severity,"INVENTORY",empty?"Insumo sin stock":"Stock bajo",message,detail,"inventory",String.valueOf(r.get("code")),actionType,actionLabel,payload,now));
        }
    }

    private void detectProduction(List<ProactiveAlert> out, OffsetDateTime now) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select po.id,po.production_number,po.status,po.pause_reason,po.scheduled_end,c.name client,m.code machine_code,
                   greatest(0,floor(extract(epoch from (now()-po.scheduled_end))/60))::bigint overdue_minutes
              from production_orders po join clients c on c.id=po.client_id join machines m on m.id=po.machine_id
             where po.status in ('PLANNED','IN_PROCESS','PAUSED') and (po.status='PAUSED' or po.scheduled_end<now())
             order by case when po.status='PAUSED' then 0 else 1 end,po.scheduled_end limit 10
            """);
        for (Map<String,Object> r : rows) {
            boolean paused = "PAUSED".equals(String.valueOf(r.get("status")));
            long minutes = ((Number)r.getOrDefault("overdue_minutes",0L)).longValue();
            String severity = paused || minutes>=120 ? "CRITICAL" : "IMPORTANT";
            String detail = r.get("client")+" · "+r.get("machine_code")+(minutes>0?" · "+minutes+" min sobre plazo":"")+(paused&&r.get("pause_reason")!=null?" · "+r.get("pause_reason"):"");
            out.add(new ProactiveAlert("production:"+r.get("id")+":"+(paused?"paused":"overdue"),severity,"PRODUCTION",paused?"OP pausada":"OP fuera de plazo",String.valueOf(r.get("production_number"))+" requiere revisión.",detail,"production",String.valueOf(r.get("production_number")),null,null,Map.of(),now));
        }
    }

    private void detectQuality(List<ProactiveAlert> out, OffsetDateTime now) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select po.id,po.production_number,po.status,po.updated_at,c.name client,
                   floor(extract(epoch from (now()-po.updated_at))/60)::bigint age_minutes
              from production_orders po join clients c on c.id=po.client_id
             where po.status in ('IN_QUALITY','REPROCESS_REQUIRED','QUALITY_REJECTED')
               and (po.status<>'IN_QUALITY' or po.updated_at<now()-interval '2 hours')
             order by case po.status when 'QUALITY_REJECTED' then 0 when 'REPROCESS_REQUIRED' then 1 else 2 end,po.updated_at limit 10
            """);
        for (Map<String,Object> r : rows) {
            String status = String.valueOf(r.get("status"));
            String severity = "QUALITY_REJECTED".equals(status)?"CRITICAL":"IMPORTANT";
            String title = switch(status){case "QUALITY_REJECTED"->"Rechazo de calidad";case "REPROCESS_REQUIRED"->"Reproceso requerido";default->"Calidad pendiente";};
            String detail = r.get("client")+" · "+((Number)r.getOrDefault("age_minutes",0L)).longValue()+" min en estado actual";
            out.add(new ProactiveAlert("quality:"+r.get("id")+":"+status,severity,"QUALITY",title,String.valueOf(r.get("production_number"))+" necesita decisión o seguimiento.",detail,"quality",String.valueOf(r.get("production_number")),null,null,Map.of(),now));
        }
    }

    private void detectDispatch(List<ProactiveAlert> out, OffsetDateTime now) {
        List<Map<String,Object>> ready = jdbc.queryForList("""
            select po.id,po.production_number,po.updated_at,c.name client,po.batch_weight_kg
              from production_orders po join clients c on c.id=po.client_id
             where po.status='READY_FOR_DISPATCH'
               and not exists(select 1 from dispatches d where d.production_order_id=po.id and d.status in ('DRAFT','READY','DISPATCHED'))
             order by po.updated_at limit 8
            """);
        for (Map<String,Object> r : ready) out.add(new ProactiveAlert("dispatch-ready:"+r.get("id"),"IMPORTANT","DISPATCH","OP lista sin despacho",r.get("production_number")+" fue liberada por Calidad y aún no tiene despacho abierto.",r.get("client")+" · "+num(r.get("batch_weight_kg"))+" kg","dispatch",String.valueOf(r.get("production_number")),null,null,Map.of(),now));

        List<Map<String,Object>> route = jdbc.queryForList("""
            select d.id,d.dispatch_number,d.actual_dispatch_at,c.name client,d.vehicle_plate
              from dispatches d join clients c on c.id=d.client_id
             where d.status='DISPATCHED' and d.actual_dispatch_at<now()-interval '12 hours'
             order by d.actual_dispatch_at limit 8
            """);
        for (Map<String,Object> r : route) out.add(new ProactiveAlert("dispatch-route:"+r.get("id"),"IMPORTANT","DISPATCH","Entrega pendiente de cierre",r.get("dispatch_number")+" continúa en ruta por más de 12 horas.",r.get("client")+(r.get("vehicle_plate")!=null?" · "+r.get("vehicle_plate"):""),"dispatch",String.valueOf(r.get("dispatch_number")),null,null,Map.of(),now));
    }

    private void detectMaintenance(List<ProactiveAlert> out, OffsetDateTime now) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select id,code,name,operational_status,current_meter_hours,next_maintenance_at,next_maintenance_meter_hours
              from machines
             where active=true and operational_status<>'RETIRED' and (
                   operational_status in ('IN_MAINTENANCE','OUT_OF_SERVICE')
                or (next_maintenance_at is not null and next_maintenance_at<=now()+interval '1 day')
                or (next_maintenance_meter_hours is not null and current_meter_hours>=next_maintenance_meter_hours))
             order by case operational_status when 'OUT_OF_SERVICE' then 0 when 'IN_MAINTENANCE' then 1 else 2 end,code limit 10
            """);
        for (Map<String,Object> r : rows) {
            String state = String.valueOf(r.get("operational_status"));
            boolean unavailable = "OUT_OF_SERVICE".equals(state)||"IN_MAINTENANCE".equals(state);
            String severity = "OUT_OF_SERVICE".equals(state)?"CRITICAL":"IMPORTANT";
            String title = unavailable?"Máquina no disponible":"Mantenimiento próximo/vencido";
            String detail = r.get("name")+" · estado "+state;
            if(r.get("next_maintenance_at")!=null) detail += " · fecha objetivo "+r.get("next_maintenance_at");
            if(r.get("next_maintenance_meter_hours")!=null) detail += " · horómetro "+num(r.get("current_meter_hours"))+"/"+num(r.get("next_maintenance_meter_hours"));
            out.add(new ProactiveAlert("maintenance:"+r.get("id")+":"+state,severity,"MAINTENANCE",title,String.valueOf(r.get("code"))+" requiere atención de mantenimiento.",detail,"maintenance",String.valueOf(r.get("code")),null,null,Map.of(),now));
        }
    }

    private void detectReturns(List<ProactiveAlert> out, OffsetDateTime now) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select cr.id,cr.return_number,cr.status,cr.return_date,cr.updated_at,c.name client
              from customer_returns cr join clients c on c.id=cr.client_id
             where cr.status not in ('CLOSED','CANCELLED') and cr.updated_at<now()-interval '24 hours'
             order by cr.updated_at limit 8
            """);
        for(Map<String,Object> r:rows) out.add(new ProactiveAlert("return:"+r.get("id")+":"+r.get("status"),"IMPORTANT","RETURNS","Devolución pendiente",r.get("return_number")+" lleva más de 24 horas abierta.",r.get("client")+" · "+r.get("status"),"returns",String.valueOf(r.get("return_number")),null,null,Map.of(),now));
    }

    private Map<String,Map<String,Object>> states(String email) {
        Map<String,Map<String,Object>> out = new HashMap<>();
        for(Map<String,Object> r:jdbc.queryForList("select alert_key,hidden_until,last_notified_at,last_action,last_seen_at from copilot_alert_user_state where user_email=?",email)) out.put(String.valueOf(r.get("alert_key")),r);
        return out;
    }

    private long scalar(String sql){Long x=jdbc.queryForObject(sql,Long.class);return x==null?0:x;}
    private boolean can(Authentication auth,String authority){return auth!=null&&auth.getAuthorities().stream().anyMatch(a->authority.equals(a.getAuthority())||"ROLE_ADMIN".equals(a.getAuthority()));}
    private BigDecimal toBig(Object x){try{return new BigDecimal(String.valueOf(x));}catch(Exception e){return BigDecimal.ZERO;}}
    private String num(Object x){if(x==null)return "0";try{return new BigDecimal(String.valueOf(x)).stripTrailingZeros().toPlainString();}catch(Exception e){return String.valueOf(x);}}
    private int severityRank(String s){return switch(s){case "CRITICAL"->0;case "IMPORTANT"->1;default->2;};}
    private OffsetDateTime asOffsetDateTime(Object v){
        if(v==null)return null;
        if(v instanceof OffsetDateTime o)return o;
        if(v instanceof Timestamp t)return t.toInstant().atOffset(ZoneOffset.UTC);
        try{return OffsetDateTime.parse(String.valueOf(v));}catch(Exception e){return null;}
    }
}
