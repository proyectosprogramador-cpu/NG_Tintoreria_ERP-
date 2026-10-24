package com.ngtintoreria.erp.inventory;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class PurchaseRequestService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    public PurchaseRequestService(JdbcTemplate jdbc, AuditService audit){this.jdbc=jdbc;this.audit=audit;}

    public record StatusRequest(String status,String comment){}
    public record DraftLine(UUID id,BigDecimal requestedQuantity){}
    public record DraftUpdate(String notes,List<DraftLine> lines){}

    public List<Map<String,Object>> list(){
        return jdbc.query("""
            select pr.id,pr.request_number,pr.production_order_id,pr.status,pr.priority,pr.notes,pr.requested_by,
                   pr.requested_at,pr.approved_by,pr.approved_at,pr.updated_at,
                   po.production_number,po.batch_number,c.name client_name,
                   count(pri.id) line_count,coalesce(sum(pri.requested_quantity),0) requested_total
              from purchase_requests pr
              left join production_orders po on po.id=pr.production_order_id
              left join clients c on c.id=po.client_id
              left join purchase_request_items pri on pri.purchase_request_id=pr.id
             group by pr.id,po.production_number,po.batch_number,c.name
             order by pr.requested_at desc
            """,(rs,row)->{
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",rs.getObject("id",UUID.class));m.put("requestNumber",rs.getString("request_number"));m.put("productionOrderId",rs.getObject("production_order_id",UUID.class));m.put("status",rs.getString("status"));m.put("priority",rs.getString("priority"));m.put("notes",rs.getString("notes"));m.put("requestedBy",rs.getString("requested_by"));m.put("requestedAt",rs.getObject("requested_at",OffsetDateTime.class));m.put("approvedBy",rs.getString("approved_by"));m.put("approvedAt",rs.getObject("approved_at",OffsetDateTime.class));m.put("updatedAt",rs.getObject("updated_at",OffsetDateTime.class));m.put("productionNumber",rs.getString("production_number"));m.put("batchNumber",rs.getString("batch_number"));m.put("clientName",rs.getString("client_name"));m.put("lineCount",rs.getInt("line_count"));m.put("requestedTotal",rs.getBigDecimal("requested_total"));return m;
        });
    }

    public Map<String,Object> detail(UUID id){
        List<Map<String,Object>> headers=jdbc.queryForList("""
            select pr.*,po.production_number,po.batch_number,c.name client_name
            from purchase_requests pr
            left join production_orders po on po.id=pr.production_order_id
            left join clients c on c.id=po.client_id
            where pr.id=?
            """,id);
        if(headers.isEmpty())throw new IllegalArgumentException("La solicitud de compra no existe");
        Map<String,Object> out=new LinkedHashMap<>();out.put("header",headers.get(0));
        out.put("items",jdbc.queryForList("select * from purchase_request_items where purchase_request_id=? order by line_no",id));
        return out;
    }

    @Transactional
    public Map<String,Object> fromProduction(UUID orderId,String actor){
        List<Map<String,Object>> orders=jdbc.queryForList("select id,production_number,priority,status,material_status from production_orders where id=? for update",orderId);
        if(orders.isEmpty())throw new IllegalArgumentException("La orden de producción no existe");
        Map<String,Object> order=orders.get(0);
        List<Map<String,Object>> existing=jdbc.queryForList("""
            select id from purchase_requests where production_order_id=? and status in ('DRAFT','REQUESTED','APPROVED')
            order by requested_at desc limit 1
            """,orderId);
        if(!existing.isEmpty())return detail((UUID)existing.get(0).get("id"));
        if(!"PLANNED".equals(order.get("status")))throw new IllegalArgumentException("La solicitud automática solo se genera desde una OP planificada");
        List<Map<String,Object>> shortages=jdbc.queryForList("""
            select pmr.id,pmr.inventory_item_id,pmr.material_code,pmr.material_name,pmr.planned_quantity_kg,pmr.shortage_quantity_kg,
                   coalesce(ii.code,pmr.material_code) inventory_code,coalesce(ii.name,pmr.material_name) inventory_name
            from production_material_requirements pmr
            left join inventory_items ii on ii.id=pmr.inventory_item_id
            where pmr.production_order_id=? and pmr.shortage_quantity_kg>0
            order by pmr.line_no
            """,orderId);
        if(shortages.isEmpty())throw new IllegalArgumentException("La OP no tiene faltantes. Primero ejecute la reserva automática de Almacén");
        Long seq=jdbc.queryForObject("select nextval('purchase_request_number_seq')",Long.class);
        String number=String.format("SC-%06d",seq==null?1:seq);
        UUID requestId=jdbc.queryForObject("""
            insert into purchase_requests(request_number,production_order_id,status,priority,notes,requested_by)
            values (?,?,'DRAFT',?,?,?) returning id
            """,UUID.class,number,orderId,String.valueOf(order.get("priority")),"Generada automáticamente por faltantes de "+order.get("production_number"),actor);
        int line=1;
        for(Map<String,Object> s:shortages){
            UUID itemId=(UUID)s.get("inventory_item_id");
            if(itemId==null)throw new IllegalArgumentException("El insumo "+s.get("material_name")+" no está vinculado al inventario");
            BigDecimal required=(BigDecimal)s.get("planned_quantity_kg"), shortage=(BigDecimal)s.get("shortage_quantity_kg");
            jdbc.update("""
                insert into purchase_request_items(purchase_request_id,inventory_item_id,material_code,material_name,
                  required_quantity,shortage_quantity,requested_quantity,unit,line_no)
                values (?,?,?,?,?,?,?,'KG',?)
                """,requestId,itemId,String.valueOf(s.get("inventory_code")),String.valueOf(s.get("inventory_name")),required,shortage,shortage,line++);
        }
        audit.record("PURCHASE_REQUEST_CREATED","Compras",requestId.toString(),number+" · origen "+order.get("production_number"));
        return detail(requestId);
    }

    @Transactional
    public Map<String,Object> updateDraft(UUID id,DraftUpdate input,String actor){
        String status=jdbc.queryForObject("select status from purchase_requests where id=? for update",String.class,id);
        if(status==null)throw new IllegalArgumentException("La solicitud no existe");
        if(!"DRAFT".equals(status))throw new IllegalArgumentException("Solo una solicitud en borrador puede editarse");
        jdbc.update("update purchase_requests set notes=?,updated_at=now() where id=?",clean(input.notes()),id);
        if(input.lines()!=null){
            for(DraftLine line:input.lines()){
                if(line.id()==null||line.requestedQuantity()==null||line.requestedQuantity().compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("Todas las cantidades solicitadas deben ser mayores que cero");
                int n=jdbc.update("update purchase_request_items set requested_quantity=? where id=? and purchase_request_id=?",line.requestedQuantity(),line.id(),id);
                if(n==0)throw new IllegalArgumentException("Una línea no pertenece a la solicitud");
            }
        }
        audit.record("PURCHASE_REQUEST_UPDATED","Compras",id.toString(),"Borrador actualizado por "+actor);
        return detail(id);
    }

    @Transactional
    public Map<String,Object> changeStatus(UUID id,StatusRequest input,String actor,boolean canApprove){
        String current=jdbc.queryForObject("select status from purchase_requests where id=? for update",String.class,id);
        if(current==null)throw new IllegalArgumentException("La solicitud no existe");
        String target=normal(input.status());
        Set<String> allowed=switch(current){case "DRAFT"->Set.of("REQUESTED","CANCELLED");case "REQUESTED"->Set.of("APPROVED","CANCELLED");case "APPROVED"->Set.of();default->Set.of();};
        if(!allowed.contains(target))throw new IllegalArgumentException("Cambio de estado no permitido: "+current+" → "+target);
        if("APPROVED".equals(target)&&!canApprove)throw new IllegalArgumentException("No tiene permiso para aprobar solicitudes de compra");
        jdbc.update("""
            update purchase_requests set status=?,approved_by=case when ?='APPROVED' then ? else approved_by end,
                   approved_at=case when ?='APPROVED' then now() else approved_at end,
                   notes=coalesce(?,notes),updated_at=now() where id=?
            """,target,target,actor,target,clean(input.comment()),id);
        audit.record("PURCHASE_REQUEST_STATUS","Compras",id.toString(),current+" → "+target+" · "+actor);
        return detail(id);
    }

    private static String clean(String s){if(s==null)return null;String v=s.trim();return v.isEmpty()?null:v;}
    private static String normal(String s){String v=clean(s);return v==null?"":v.toUpperCase(Locale.ROOT);}
}
