package com.ngtintoreria.erp.copilot;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CopilotService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CopilotAiClient ai;
    private static final Pattern REF = Pattern.compile("\\b(OP-\\d+|PED-\\d+|DEV-\\d+|COT-\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile("(?:a\\s+las|a\\s+la)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE);

    public CopilotService(JdbcTemplate jdbc, AuditService audit, CopilotAiClient ai) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.ai = ai;
    }

    public record Fact(String label, String value, String detail, String severity, String route) {}
    public record ProposedAction(String type, String title, String message, String suggestedAt) {}
    public record AskResult(String answer, String intent, String mode, List<Fact> facts,
                            List<String> suggestions, ProposedAction action) {}

    public AskResult ask(String rawMessage, String context, Authentication auth) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isBlank()) throw new IllegalArgumentException("Escriba una consulta para NG Copiloto");
        if (message.length() > 900) throw new IllegalArgumentException("La consulta es demasiado larga");
        String q = normalize(message);
        String safeContext = context == null ? "" : context.trim().toLowerCase(Locale.ROOT);

        AskResult local;
        boolean hasReference = REF.matcher(message).find();
        boolean asksCost = containsAny(q,"costo","costos","margen","rentabilidad","ganancia","utilidad");
        if (q.contains("recuerd") || q.contains("recordatorio")) local = reminderProposal(message, q);
        else if (hasReference && asksCost) local = costingReference(message, auth);
        else if (containsAny(q,"como hago","como registrar","como se registra","que pasos","ayudame","ayuda","que hago","que puedo hacer","que puedo consultar","desde este modulo")) local = help(q, safeContext);
        else if (hasReference) local = referenceLookup(message, auth);
        else if (containsAny(q,"produccion","orden de produccion","ordenes de produccion"," op ","atrasad","demorad","pendiente hoy")) local = production(auth);
        else if (containsAny(q,"calidad","liberacion","rechazad","observad")) local = quality(auth);
        else if (containsAny(q,"mantenimiento","maquina","maquinas","jet ","horometro","parada")) local = maintenance(auth);
        else if (containsAny(q,"despacho","entrega","reparto","en ruta")) local = dispatch(auth);
        else if (containsAny(q,"stock","inventario","almacen","insumo","faltante")) local = inventory(auth);
        else if (containsAny(q,"devolucion","reproceso","reclamo")) local = returns(auth);
        else if (containsAny(q,"costo","costos","margen","rentabilidad","ganancia","utilidad")) local = costing(auth);
        else local = summary(auth);

        String mode = ai.enabled() ? "IA + datos autorizados" : "Motor local seguro";
        String answer = local.answer();
        if (ai.enabled() && local.action() == null) {
            String generated = ai.generate(message, safeContext, factsForAi(local), answer);
            if (generated != null && !generated.isBlank()) answer = generated.trim();
        }
        AskResult out = new AskResult(answer, local.intent(), mode, local.facts(), local.suggestions(), local.action());
        audit.record("COPILOT_QUERY", "NG Copiloto", null,
                "Intent=" + out.intent() + " · Contexto=" + (safeContext.isBlank()?"general":safeContext) + " · Consulta=" + truncate(message,260));
        return out;
    }

    public Map<String,Object> capabilities(Authentication auth) {
        LinkedHashMap<String,Object> out = new LinkedHashMap<>();
        out.put("mode", ai.enabled()?"IA + datos autorizados":"Motor local seguro");
        out.put("generativeAiEnabled", ai.enabled());
        List<Map<String,String>> quick = new ArrayList<>();
        quick.add(Map.of("label","Resumen de hoy","prompt","¿Cómo está la operación hoy?","icon","📊"));
        if(can(auth,"PRODUCTION_ORDERS_READ")) quick.add(Map.of("label","Producción","prompt","¿Qué órdenes de producción requieren atención?","icon","🏭"));
        if(can(auth,"QUALITY_READ")) quick.add(Map.of("label","Calidad","prompt","¿Qué está pendiente en Control de Calidad?","icon","🧪"));
        if(can(auth,"MAINTENANCE_READ")) quick.add(Map.of("label","Máquinas","prompt","¿Qué máquinas o mantenimientos requieren atención?","icon","🔧"));
        if(can(auth,"DISPATCH_READ")) quick.add(Map.of("label","Despachos","prompt","¿Qué despachos están pendientes?","icon","🚚"));
        if(can(auth,"INVENTORY_READ")) quick.add(Map.of("label","Stock crítico","prompt","¿Qué insumos tienen stock crítico?","icon","📦"));
        if(can(auth,"RETURNS_READ")) quick.add(Map.of("label","Devoluciones","prompt","¿Hay devoluciones o reprocesos abiertos?","icon","↩️"));
        if(can(auth,"COSTING_READ")) quick.add(Map.of("label","Costos","prompt","Dame un resumen de costos y margen","icon","💰"));
        out.put("quickActions", quick);
        out.put("voiceSupportedByBrowser", true);
        out.put("reminders", true);
        out.put("proactiveAlerts", true);
        out.put("shiftBriefing", true);
        out.put("confirmableActions", true);
        return out;
    }

    private AskResult summary(Authentication auth) {
        List<Fact> f = new ArrayList<>();
        List<String> sentences = new ArrayList<>();
        if(can(auth,"PRODUCTION_ORDERS_READ")) {
            long active=scalar("select count(*) from production_orders where status in ('PLANNED','IN_PROCESS','PAUSED','IN_QUALITY','REPROCESS_REQUIRED','READY_FOR_DISPATCH','PARTIALLY_DISPATCHED')");
            long late=scalar("select count(*) from production_orders where scheduled_end<now() and status in ('PLANNED','IN_PROCESS','PAUSED','IN_QUALITY','REPROCESS_REQUIRED')");
            f.add(new Fact("Producción activa",String.valueOf(active),late>0?late+" con plazo vencido":"Sin OP vencidas",late>0?"warn":"ok","production"));
            sentences.add(active+" órdenes de producción activas"+(late>0?"; "+late+" requieren atención por plazo":""));
        }
        if(can(auth,"QUALITY_READ")) {
            long pending=scalar("select count(*) from production_orders where status in ('IN_QUALITY','REPROCESS_REQUIRED','QUALITY_REJECTED')");
            f.add(new Fact("Calidad",String.valueOf(pending),pending>0?"Órdenes pendientes de decisión o reproceso":"Sin pendientes críticos",pending>0?"warn":"ok","quality"));
            sentences.add(pending+" órdenes en circuito de calidad");
        }
        if(can(auth,"DISPATCH_READ")) {
            long pending=scalar("select count(*) from dispatches where status in ('DRAFT','READY','DISPATCHED')");
            f.add(new Fact("Despachos pendientes",String.valueOf(pending),"Borrador, listos o en ruta",pending>0?"info":"ok","dispatch"));
            sentences.add(pending+" despachos aún no cerrados");
        }
        if(can(auth,"MAINTENANCE_READ")) {
            long open=scalar("select count(*) from maintenance_work_orders where status in ('PLANNED','IN_PROGRESS')");
            long unavailable=scalar("select count(*) from machines where active=true and operational_status in ('IN_MAINTENANCE','OUT_OF_SERVICE')");
            f.add(new Fact("Mantenimiento",String.valueOf(open),unavailable+" máquinas no disponibles",unavailable>0?"warn":"info","maintenance"));
        }
        if(can(auth,"INVENTORY_READ")) {
            long critical=scalar("""
                with physical as (select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id),
                     reserved as (select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id)
                select count(*) from inventory_items i left join physical p on p.inventory_item_id=i.id left join reserved r on r.inventory_item_id=i.id
                where i.active=true and greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0)<=i.minimum_stock
                """);
            f.add(new Fact("Stock crítico/bajo",String.valueOf(critical),"Según mínimo configurado y reservas activas",critical>0?"warn":"ok","inventory"));
        }
        if(f.isEmpty()) return denied("resumen","No tienes permisos de consulta sobre módulos operativos todavía.");
        String answer="Resumen operativo: "+String.join("; ",sentences)+". Puedo abrir el detalle de cualquiera de estos puntos si me lo preguntas.";
        return new AskResult(answer,"summary","",f,List.of("¿Qué requiere atención primero?","¿Qué puedo hacer desde este módulo?"),null);
    }

    private AskResult production(Authentication auth) {
        if(!can(auth,"PRODUCTION_ORDERS_READ")) return denied("production","No tienes permiso para consultar órdenes de producción.");
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select po.production_number,po.status,c.name client,m.code machine_code,m.name machine_name,po.scheduled_end,
                   case when po.scheduled_end<now() then true else false end overdue
            from production_orders po join clients c on c.id=po.client_id join machines m on m.id=po.machine_id
            where po.status in ('PLANNED','IN_PROCESS','PAUSED','IN_QUALITY','REPROCESS_REQUIRED','READY_FOR_DISPATCH','PARTIALLY_DISPATCHED')
            order by case when po.scheduled_end<now() then 0 else 1 end,po.scheduled_end asc limit 8
            """);
        long late=rows.stream().filter(r->Boolean.TRUE.equals(r.get("overdue"))).count();
        List<Fact> facts=rows.stream().map(r->new Fact(String.valueOf(r.get("production_number")),statusEs(r.get("status")),
                r.get("client")+" · "+r.get("machine_code")+" · fin "+date(r.get("scheduled_end")),Boolean.TRUE.equals(r.get("overdue"))?"warn":"info","production")).toList();
        String answer=rows.isEmpty()?"No hay órdenes activas de producción en este momento.":"Encontré "+rows.size()+" órdenes activas en la primera prioridad de revisión"+(late>0?"; "+late+" ya superaron su fin programado":"")+".";
        return new AskResult(answer,"production","",facts,List.of("¿Qué OP está atrasada?","Explícame cómo iniciar una OP","¿Qué está pendiente en calidad?"),null);
    }

    private AskResult quality(Authentication auth) {
        if(!can(auth,"QUALITY_READ")) return denied("quality","No tienes permiso para consultar Control de Calidad.");
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select po.production_number,po.status,c.name client,coalesce(qi.inspection_number,'Sin inspección') inspection_number,
                   coalesce(qi.result,'PENDING') result,qi.created_at
              from production_orders po join clients c on c.id=po.client_id
              left join lateral (select * from quality_inspections q where q.production_order_id=po.id order by q.attempt_no desc limit 1) qi on true
             where po.status in ('IN_QUALITY','REPROCESS_REQUIRED','QUALITY_REJECTED')
             order by po.updated_at asc limit 8
            """);
        List<Fact> facts=rows.stream().map(r->new Fact(String.valueOf(r.get("production_number")),statusEs(r.get("result")),
                r.get("client")+" · "+r.get("inspection_number"),"REPROCESS".equals(String.valueOf(r.get("result")))||"REJECTED".equals(String.valueOf(r.get("result")))?"warn":"info","quality")).toList();
        return new AskResult(rows.isEmpty()?"No veo órdenes pendientes en el circuito de Calidad.":"Hay "+rows.size()+" órdenes que requieren revisión o seguimiento de Calidad.","quality","",facts,
                List.of("¿Cómo libero una OP aprobada?","¿Cómo envío una OP a reproceso?"),null);
    }

    private AskResult maintenance(Authentication auth) {
        if(!can(auth,"MAINTENANCE_READ")) return denied("maintenance","No tienes permiso para consultar mantenimiento de máquinas.");
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select mw.maintenance_number,mw.status,mw.title,mw.scheduled_start,m.code machine_code,m.name machine_name,m.operational_status
              from maintenance_work_orders mw join machines m on m.id=mw.machine_id
             where mw.status in ('PLANNED','IN_PROGRESS')
             order by case when mw.status='IN_PROGRESS' then 0 else 1 end,mw.scheduled_start asc limit 8
            """);
        long unavailable=scalar("select count(*) from machines where active=true and operational_status in ('IN_MAINTENANCE','OUT_OF_SERVICE')");
        List<Fact> facts=new ArrayList<>();
        if(unavailable>0) facts.add(new Fact("Máquinas no disponibles",String.valueOf(unavailable),"En mantenimiento o fuera de servicio","warn","maintenance"));
        for(Map<String,Object> r:rows) facts.add(new Fact(String.valueOf(r.get("maintenance_number")),statusEs(r.get("status")),r.get("machine_code")+" · "+r.get("title")+" · "+date(r.get("scheduled_start")),"IN_PROGRESS".equals(r.get("status"))?"warn":"info","maintenance"));
        return new AskResult(rows.isEmpty()&&unavailable==0?"No hay mantenimientos abiertos ni máquinas marcadas como no disponibles.":"Mantenimiento tiene "+rows.size()+" órdenes abiertas y "+unavailable+" máquinas no disponibles.","maintenance","",facts,
                List.of("¿Cómo registro un mantenimiento?","¿Qué máquinas están fuera de servicio?"),null);
    }

    private AskResult dispatch(Authentication auth) {
        if(!can(auth,"DISPATCH_READ")) return denied("dispatch","No tienes permiso para consultar Despacho / Entrega.");
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select d.dispatch_number,d.status,d.dispatch_date,c.name client,d.total_weight_kg,d.vehicle_plate,d.driver_name
              from dispatches d join clients c on c.id=d.client_id
             where d.status in ('DRAFT','READY','DISPATCHED')
             order by d.dispatch_date asc,d.created_at asc limit 8
            """);
        List<Fact> facts=rows.stream().map(r->new Fact(String.valueOf(r.get("dispatch_number")),statusEs(r.get("status")),
                r.get("client")+" · "+num(r.get("total_weight_kg"))+" kg"+(r.get("vehicle_plate")!=null?" · "+r.get("vehicle_plate"):""),"DISPATCHED".equals(r.get("status"))?"warn":"info","dispatch")).toList();
        return new AskResult(rows.isEmpty()?"No hay despachos pendientes de cierre.":"Hay "+rows.size()+" despachos en borrador, listos o en ruta.","dispatch","",facts,List.of("¿Cómo confirmo una entrega?","¿Qué está listo para despacho?"),null);
    }

    private AskResult inventory(Authentication auth) {
        if(!can(auth,"INVENTORY_READ")) return denied("inventory","No tienes permiso para consultar Almacén / Kardex.");
        List<Map<String,Object>> rows=jdbc.queryForList("""
            with physical as (select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id),
                 reserved as (select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id)
            select i.code,i.name,i.base_unit,i.minimum_stock,
                   greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0) available
              from inventory_items i left join physical p on p.inventory_item_id=i.id left join reserved r on r.inventory_item_id=i.id
             where i.active=true and greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0)<=i.minimum_stock
             order by greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0),lower(i.name) limit 10
            """);
        long zero=rows.stream().filter(r->toBig(r.get("available")).signum()==0).count();
        long low=rows.size()-zero;
        List<Fact> facts=rows.stream().map(r->{
            boolean noStock=toBig(r.get("available")).signum()==0;
            String detail=(noStock?"Sin stock":"Stock bajo")+" · "+r.get("name")+" · mínimo "+num(r.get("minimum_stock"));
            return new Fact(String.valueOf(r.get("code")),num(r.get("available"))+" "+r.get("base_unit"),detail,noStock?"warn":"info","inventory");
        }).toList();
        String answer;
        if(rows.isEmpty()) answer="No detecto insumos sin stock ni por debajo del mínimo configurado.";
        else {
            List<String> parts=new ArrayList<>();
            if(zero>0) parts.add(zero+" "+(zero==1?"insumo sin stock":"insumos sin stock"));
            if(low>0) parts.add(low+" "+(low==1?"insumo con stock bajo":"insumos con stock bajo"));
            answer="Detecté "+String.join(" y ",parts)+".";
        }
        return new AskResult(answer,"inventory","",facts,
                List.of("¿Qué insumos están en cero?","¿Cómo genero una solicitud de compra?"),null);
    }

    private AskResult returns(Authentication auth) {
        if(!can(auth,"RETURNS_READ")) return denied("returns","No tienes permiso para consultar Devoluciones / Reproceso.");
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select cr.return_number,cr.status,cr.decision,cr.return_date,c.name client,cr.total_weight_kg
              from customer_returns cr join clients c on c.id=cr.client_id
             where cr.status not in ('CLOSED','CANCELLED')
             order by cr.return_date asc,cr.created_at asc limit 8
            """);
        List<Fact> facts=rows.stream().map(r->new Fact(String.valueOf(r.get("return_number")),statusEs(r.get("status")),
                r.get("client")+" · decisión "+statusEs(r.get("decision"))+" · "+num(r.get("total_weight_kg"))+" kg","warn","returns")).toList();
        return new AskResult(rows.isEmpty()?"No hay devoluciones abiertas.":"Hay "+rows.size()+" devoluciones o reclamos todavía abiertos.","returns","",facts,
                List.of("¿Cómo registro una devolución?","¿Cómo creo un reproceso?"),null);
    }

    private AskResult costing(Authentication auth) {
        if(!can(auth,"COSTING_READ")) return denied("costing","No tienes permiso para consultar información de costos o margen.");
        Map<String,Object> t=jdbc.queryForMap("""
            select coalesce(sum(total_cost),0) total_cost,coalesce(sum(allocated_sale_amount_base),0) total_sale,
                   coalesce(sum(gross_margin),0) gross_margin,
                   case when sum(allocated_sale_amount_base)>0 then round((sum(gross_margin)/sum(allocated_sale_amount_base)*100)::numeric,2) else null end margin_pct,
                   count(*) costed
              from production_cost_snapshots where current_snapshot=true
            """);
        List<Fact> f=List.of(
                new Fact("Órdenes costeadas",String.valueOf(t.get("costed")),"Snapshots vigentes","info","costing"),
                new Fact("Costo acumulado","S/ "+money(t.get("total_cost")),"Órdenes costeadas","info","costing"),
                new Fact("Margen bruto","S/ "+money(t.get("gross_margin")),t.get("margin_pct")==null?"Margen % no disponible":num(t.get("margin_pct"))+" %","info","costing")
        );
        return new AskResult("El costeo consolidado muestra "+t.get("costed")+" órdenes con snapshot vigente. La información financiera solo se muestra a roles autorizados.","costing","",f,
                List.of("¿Qué OP tiene menor margen?","Explícame cómo se calcula el costo industrial"),null);
    }

    private AskResult costingReference(String message, Authentication auth) {
        if(!can(auth,"COSTING_READ")) return denied("costing","No tienes permiso para consultar información de costos o margen.");
        Matcher m=REF.matcher(message);
        if(!m.find()) return costing(auth);
        String ref=m.group(1).toUpperCase(Locale.ROOT);
        if(!ref.startsWith("OP-")) return costing(auth);
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select po.production_number,po.status,c.name client,pcs.total_cost,pcs.allocated_sale_amount_base,
                   pcs.gross_margin,pcs.margin_pct,pcs.cost_per_output_kg,pcs.unvalued_material_qty,pcs.calculated_at
              from production_orders po
              join clients c on c.id=po.client_id
              left join production_cost_snapshots pcs on pcs.production_order_id=po.id and pcs.current_snapshot=true
             where upper(po.production_number)=?
            """,ref);
        if(rows.isEmpty()) return new AskResult("No encontré "+ref+".","costing-reference","",List.of(),List.of("Dame un resumen de costos y margen"),null);
        Map<String,Object> r=rows.get(0);
        if(r.get("total_cost")==null) return new AskResult(ref+" existe, pero todavía no tiene un snapshot de costeo vigente.","costing-reference","",
                List.of(new Fact(ref,statusEs(r.get("status")),String.valueOf(r.get("client")),"info","costing")),
                List.of("Dame un resumen de costos y margen","¿Cómo se calcula el costo industrial?"),null);
        String marginPct=r.get("margin_pct")==null?"—":num(r.get("margin_pct"))+" %";
        List<Fact> facts=List.of(
                new Fact("Venta asignada","S/ "+money(r.get("allocated_sale_amount_base")),r.get("client")+" · "+statusEs(r.get("status")),"info","costing"),
                new Fact("Costo real","S/ "+money(r.get("total_cost")),"Costo por kg: S/ "+money(r.get("cost_per_output_kg")),"info","costing"),
                new Fact("Margen bruto","S/ "+money(r.get("gross_margin")),"Margen: "+marginPct,"ok","costing")
        );
        String answer=ref+" tiene venta asignada S/ "+money(r.get("allocated_sale_amount_base"))+", costo real S/ "+money(r.get("total_cost"))+
                " y margen bruto S/ "+money(r.get("gross_margin"))+" ("+marginPct+").";
        return new AskResult(answer,"costing-reference","",facts,List.of("Explícame cómo se calcula el costo industrial","Dame un resumen de costos y margen"),null);
    }

    private AskResult referenceLookup(String message, Authentication auth) {
        Matcher m=REF.matcher(message); if(!m.find()) return summary(auth);
        String ref=m.group(1).toUpperCase(Locale.ROOT);
        if(ref.startsWith("OP-")) {
            if(!can(auth,"PRODUCTION_ORDERS_READ")) return denied("reference","No tienes permiso para consultar esa OP.");
            List<Map<String,Object>> rows=jdbc.queryForList("""
                select po.production_number,po.status,po.material_status,po.batch_number,po.batch_weight_kg,po.scheduled_start,po.scheduled_end,
                       c.name client,m.code machine_code,m.name machine_name
                  from production_orders po join clients c on c.id=po.client_id join machines m on m.id=po.machine_id
                 where upper(po.production_number)=?
                """,ref);
            if(rows.isEmpty()) return new AskResult("No encontré "+ref+".","reference","",List.of(),List.of("¿Qué OP están activas?"),null);
            Map<String,Object> r=rows.get(0);
            return new AskResult(ref+" está en estado "+statusEs(r.get("status"))+" y material "+statusEs(r.get("material_status"))+".","reference","",
                    List.of(new Fact(ref,statusEs(r.get("status")),r.get("client")+" · "+r.get("machine_code")+" · "+num(r.get("batch_weight_kg"))+" kg · fin "+date(r.get("scheduled_end")),"info","production")),
                    List.of("¿Qué falta para completar esta OP?","¿Está atrasada?"),null);
        }
        if(ref.startsWith("PED-")) {
            if(!can(auth,"ORDERS_READ")) return denied("reference","No tienes permiso para consultar pedidos.");
            List<Map<String,Object>> rows=jdbc.queryForList("""
                select so.order_number,so.status,so.created_at,c.name client,count(soi.id) items
                  from sales_orders so join clients c on c.id=so.client_id left join sales_order_items soi on soi.sales_order_id=so.id
                 where upper(so.order_number)=? group by so.order_number,so.status,so.created_at,c.name
                """,ref);
            if(rows.isEmpty()) return new AskResult("No encontré "+ref+".","reference","",List.of(),List.of("¿Qué pedidos están pendientes?"),null);
            Map<String,Object> r=rows.get(0);
            return new AskResult(ref+" pertenece a "+r.get("client")+" y está "+statusEs(r.get("status"))+".","reference","",
                    List.of(new Fact(ref,statusEs(r.get("status")),r.get("client")+" · "+r.get("items")+" líneas","info","orders")),List.of("¿Tiene OP asociada?","¿Qué falta para despacharlo?"),null);
        }
        if(ref.startsWith("DEV-")) {
            if(!can(auth,"RETURNS_READ")) return denied("reference","No tienes permiso para consultar devoluciones.");
            List<Map<String,Object>> rows=jdbc.queryForList("""
                select cr.return_number,cr.status,cr.decision,cr.reason_code,cr.total_weight_kg,c.name client
                  from customer_returns cr join clients c on c.id=cr.client_id where upper(cr.return_number)=?
                """,ref);
            if(rows.isEmpty()) return new AskResult("No encontré "+ref+".","reference","",List.of(),List.of("¿Hay devoluciones abiertas?"),null);
            Map<String,Object> r=rows.get(0);
            return new AskResult(ref+" está "+statusEs(r.get("status"))+" con decisión "+statusEs(r.get("decision"))+".","reference","",
                    List.of(new Fact(ref,statusEs(r.get("status")),r.get("client")+" · "+statusEs(r.get("reason_code"))+" · "+num(r.get("total_weight_kg"))+" kg","warn","returns")),List.of("¿Cómo creo un reproceso?"),null);
        }
        return new AskResult("Reconocí la referencia "+ref+", pero todavía no tengo una consulta contextual habilitada para ese tipo.","reference","",List.of(),List.of("Dame un resumen de hoy"),null);
    }

    private AskResult help(String q, String context) {
        String key=context;
        if(containsAny(q,"devolucion","reproceso")) key="returns";
        else if(containsAny(q,"calidad","liberar")) key="quality";
        else if(containsAny(q,"mantenimiento","maquina")) key="maintenance";
        else if(containsAny(q,"produccion"," op ","orden")) key="production";
        else if(containsAny(q,"despacho","entrega")) key="dispatch";
        else if(containsAny(q,"stock","inventario","almacen")) key="inventory";
        else if(containsAny(q,"cotizacion","proforma")) key="quotes";
        String text=switch(key){
            case "production" -> "Para trabajar una OP: 1) confirma programación y máquina; 2) verifica/reserva materiales; 3) inicia la OP; 4) registra operaciones y consumos reales; 5) finaliza producción; 6) envíala a Control de Calidad. Si el sistema bloquea un paso, revisa el requisito que aparece en pantalla antes de continuar.";
            case "quality" -> "En Control de Calidad: 1) abre la OP; 2) registra la inspección y controles; 3) adjunta evidencia si corresponde; 4) envía la inspección; 5) decide aprobar, observar, reprocesar o rechazar. Solo una decisión autorizada debe cambiar el flujo de la OP.";
            case "returns" -> "Para una devolución: 1) parte de un despacho entregado; 2) registra motivo y rollos devueltos; 3) recepciona; 4) inspecciona; 5) decide reproceso, reposición, crédito o rechazo; 6) si corresponde, crea la OP de reproceso; 7) cierra la devolución cuando la resolución esté completada.";
            case "maintenance" -> "Para mantenimiento: 1) selecciona la máquina; 2) crea la OT con ventana y responsable; 3) inicia la OT al comenzar; 4) registra trabajo, parada, repuestos y costos; 5) completa indicando el estado final. Una máquina fuera de servicio no debe recibir nuevas programaciones.";
            case "dispatch" -> "Para despachar: 1) la OP debe estar liberada por Calidad; 2) crea el despacho y selecciona rollos; 3) completa transporte/chofer/placa; 4) confirma salida; 5) al entregar registra receptor, fecha y observaciones. No fuerces un despacho si Calidad no lo liberó.";
            case "inventory" -> "En Almacén: revisa existencias y reservas, registra movimientos con referencia de origen y usa Solicitud de Compra cuando una OP tenga faltantes. Evita ajustes manuales sin motivo porque afectan el Kardex y el costeo.";
            case "quotes" -> "Para una cotización/proforma: selecciona cliente, agrega tela/proceso/acabado y tarifa, revisa vigencia y total, envía a aprobación cuando corresponda y recién después conviértela en pedido. No dupliques el pedido si ya existe uno asociado.";
            default -> "Puedo explicarte el flujo del módulo actual y consultar el estado real de la operación. Prueba: “¿qué está pendiente hoy?”, “¿qué OP requieren atención?”, “¿cómo registro una devolución?” o menciona una referencia como OP-000025.";
        };
        return new AskResult(text,"help","",List.of(),List.of("¿Qué está pendiente hoy?","¿Qué puedo consultar aquí?"),null);
    }

    private AskResult reminderProposal(String original, String normalized) {
        String message=original.replaceFirst("(?i).*?recu[eé]rdame(?:\\s+que|\\s+a)?\\s*","").trim();
        if(message.isBlank()) message="Recordatorio de NG Copiloto";
        OffsetDateTime suggested=parseReminderTime(normalized);
        String answer=suggested==null?"Puedo crear el recordatorio, pero necesito una fecha y hora. Usa el botón de recordatorio o escribe, por ejemplo: “Recuérdame mañana a las 15:00 revisar la OP-000025”.":"Preparé un recordatorio para "+date(suggested)+". No se guardará hasta que confirmes.";
        return new AskResult(answer,"reminder","",List.of(),List.of("Dame un resumen de hoy"),new ProposedAction("CREATE_REMINDER","Crear recordatorio",message,suggested==null?null:suggested.toString()));
    }

    private OffsetDateTime parseReminderTime(String q) {
        Matcher m=TIME.matcher(q); if(!m.find()) return null;
        int h=Integer.parseInt(m.group(1)); int min=m.group(2)==null?0:Integer.parseInt(m.group(2)); String ap=m.group(3);
        if(ap!=null){ if("pm".equalsIgnoreCase(ap)&&h<12)h+=12; if("am".equalsIgnoreCase(ap)&&h==12)h=0; }
        if(h>23||min>59) return null;
        ZoneId zone=ZoneId.of("America/Lima"); LocalDate d=LocalDate.now(zone);
        if(q.contains("manana")) d=d.plusDays(1);
        ZonedDateTime z=ZonedDateTime.of(d,LocalTime.of(h,min),zone);
        if(!q.contains("manana") && z.isBefore(ZonedDateTime.now(zone))) z=z.plusDays(1);
        return z.toOffsetDateTime();
    }

    @Transactional
    public Map<String,Object> createReminder(String userEmail, String message, OffsetDateTime remindAt) {
        if(message==null||message.isBlank()) throw new IllegalArgumentException("Escriba el mensaje del recordatorio");
        if(message.length()>700) throw new IllegalArgumentException("El recordatorio es demasiado largo");
        if(remindAt==null) throw new IllegalArgumentException("Indique fecha y hora del recordatorio");
        OffsetDateTime now=OffsetDateTime.now();
        if(remindAt.isBefore(now.minusMinutes(1))) throw new IllegalArgumentException("La fecha del recordatorio ya pasó");
        if(remindAt.isAfter(now.plusYears(1))) throw new IllegalArgumentException("El recordatorio no puede programarse a más de un año");
        UUID id=jdbc.queryForObject("insert into copilot_reminders(user_email,message,remind_at) values (?,?,?) returning id",UUID.class,userEmail,message.trim(),remindAt);
        audit.record("COPILOT_REMINDER_CREATED","NG Copiloto",id==null?null:id.toString(),truncate(message,260)+" · "+remindAt);
        return jdbc.queryForMap("select id,message,remind_at,status,created_at from copilot_reminders where id=?",id);
    }

    public List<Map<String,Object>> reminders(String userEmail, int limit) {
        int safe=Math.min(Math.max(limit,1),50);
        return jdbc.queryForList("select id,message,remind_at,status,delivered_at,completed_at,created_at from copilot_reminders where user_email=? order by case when status='PENDING' then 0 else 1 end,remind_at desc limit ?",userEmail,safe);
    }

    @Transactional
    public List<Map<String,Object>> dueReminders(String userEmail) {
        List<Map<String,Object>> due=jdbc.queryForList("select id,message,remind_at,status from copilot_reminders where user_email=? and status='PENDING' and remind_at<=now() and delivered_at is null order by remind_at limit 10",userEmail);
        for(Map<String,Object> r:due) jdbc.update("update copilot_reminders set delivered_at=now() where id=? and delivered_at is null",r.get("id"));
        return due;
    }

    @Transactional
    public void completeReminder(String userEmail, UUID id, boolean dismiss) {
        int n=jdbc.update("update copilot_reminders set status=?,completed_at=now() where id=? and user_email=? and status='PENDING'",dismiss?"DISMISSED":"DONE",id,userEmail);
        if(n==0) throw new IllegalArgumentException("El recordatorio no existe o ya fue cerrado");
        audit.record(dismiss?"COPILOT_REMINDER_DISMISSED":"COPILOT_REMINDER_DONE","NG Copiloto",id.toString(),"Recordatorio cerrado");
    }

    private AskResult denied(String intent,String text){return new AskResult(text,intent,"",List.of(),List.of("¿Qué puedo consultar con mi rol?"),null);}
    private long scalar(String sql){Long x=jdbc.queryForObject(sql,Long.class);return x==null?0:x;}
    private boolean can(Authentication auth,String authority){return auth!=null&&auth.getAuthorities().stream().anyMatch(a->authority.equals(a.getAuthority())||"ROLE_ADMIN".equals(a.getAuthority()));}
    private boolean containsAny(String s,String... xs){for(String x:xs)if(s.contains(x))return true;return false;}
    private String normalize(String s){String n=Normalizer.normalize(s.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}+","");return " "+n.replaceAll("\\s+"," ").trim()+" ";}
    private String truncate(String s,int max){return s==null?"":(s.length()<=max?s:s.substring(0,max));}
    private String statusEs(Object x){if(x==null)return "—";return switch(String.valueOf(x)){
        case "PLANNED"->"Programada";case "IN_PROCESS"->"En proceso";case "PAUSED"->"Pausada";case "IN_QUALITY"->"En calidad";case "REPROCESS_REQUIRED"->"Reproceso requerido";case "QUALITY_REJECTED"->"Rechazada por calidad";case "READY_FOR_DISPATCH"->"Lista para despacho";case "PARTIALLY_DISPATCHED"->"Despacho parcial";case "DISPATCHED"->"Despachada / en ruta";case "DELIVERED"->"Entregada";case "DRAFT"->"Borrador";case "READY"->"Lista";case "APPROVED"->"Aprobada";case "OBSERVED"->"Observada";case "REPROCESS"->"Reproceso";case "REJECTED"->"Rechazada";case "PENDING"->"Pendiente";case "AVAILABLE"->"Disponible";case "CONSUMED"->"Consumido";case "SHORTAGE"->"Faltante";case "RESERVED"->"Reservado";case "IN_PROGRESS"->"En curso";case "CLOSED"->"Cerrada";case "RECEIVED"->"Recibida";case "UNDER_REVIEW"->"En revisión";case "DECIDED"->"Decidida";case "REPROCESS_CREATED"->"Reproceso creado";case "REPLACEMENT"->"Reposición";case "CREDIT"->"Crédito";case "FINISH"->"Acabado";case "COLOR_VARIATION"->"Variación de color";default->String.valueOf(x).replace('_',' ');};}
    private String date(Object x){if(x==null)return "—"; if(x instanceof OffsetDateTime o)return o.format(DateTimeFormatter.ofPattern("dd/MM HH:mm")); if(x instanceof java.sql.Timestamp t)return t.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM HH:mm")); return String.valueOf(x);}
    private String num(Object x){if(x==null)return "0";try{return new BigDecimal(String.valueOf(x)).stripTrailingZeros().toPlainString();}catch(Exception e){return String.valueOf(x);}}
    private String money(Object x){try{return String.format(Locale.forLanguageTag("es-PE"),"%,.2f",new BigDecimal(String.valueOf(x)));}catch(Exception e){return "0.00";}}
    private BigDecimal toBig(Object x){try{return new BigDecimal(String.valueOf(x));}catch(Exception e){return BigDecimal.ZERO;}}
    private String factsForAi(AskResult local){StringBuilder b=new StringBuilder(); for(Fact f:local.facts())b.append("- ").append(f.label()).append(": ").append(f.value()).append(". ").append(f.detail()).append('\n'); return b.toString();}
}
