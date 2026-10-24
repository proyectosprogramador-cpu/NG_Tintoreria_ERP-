package com.ngtintoreria.erp.production;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.inventory.InventoryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class ProductionExecutionService {
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final AuditService audit;

    public ProductionExecutionService(JdbcTemplate jdbc, InventoryService inventory, AuditService audit) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.audit = audit;
    }

    public record MaterialActualRequest(BigDecimal actualQuantityKg, String comment) {}
    public record ExecutionSummaryRequest(BigDecimal actualOutputWeightKg, BigDecimal reprocessWeightKg,
                                          BigDecimal wasteWeightKg, BigDecimal waterConsumptionL,
                                          BigDecimal energyKwh, String executionNotes) {}
    public record IncidentRequest(String incidentType, String severity, Integer downtimeMinutes,
                                  String description, String correctiveAction) {}

    public Map<String,Object> snapshot(UUID orderId) {
        Map<String,Object> out = new LinkedHashMap<>();
        List<Map<String,Object>> summary = jdbc.queryForList("""
            select po.actual_output_weight_kg,po.reprocess_weight_kg,po.waste_weight_kg,po.water_consumption_l,
                   po.energy_kwh,po.execution_notes,po.actual_start,po.actual_end,
                   case when po.actual_start is not null then
                     extract(epoch from (coalesce(po.actual_end,now())-po.actual_start))/60 else null end actual_duration_minutes,
                   (select count(*) from production_material_requirements pmr where pmr.production_order_id=po.id) material_lines,
                   (select count(*) from production_material_requirements pmr where pmr.production_order_id=po.id and pmr.consumption_confirmed=true) confirmed_material_lines,
                   (select count(*) from production_incidents pi where pi.production_order_id=po.id) incident_count,
                   (select coalesce(sum(pi.downtime_minutes),0) from production_incidents pi where pi.production_order_id=po.id) downtime_minutes
              from production_orders po where po.id=?
            """, orderId);
        out.put("summary", summary.isEmpty()?Map.of():summary.get(0));
        out.put("consumptions", jdbc.queryForList("""
            select c.id,c.movement_kind,c.quantity_kg,c.comment,c.actor,c.created_at,
                   pmr.line_no,pmr.material_code,pmr.material_name
              from production_material_consumptions c
              join production_material_requirements pmr on pmr.id=c.production_material_requirement_id
             where c.production_order_id=? order by c.created_at desc,c.id desc
            """, orderId));
        out.put("incidents", jdbc.queryForList("""
            select id,incident_type,severity,downtime_minutes,description,corrective_action,actor,created_at
              from production_incidents where production_order_id=? order by created_at desc,id desc
            """, orderId));
        Map<String,Object> quality=one("""
            select inspection_number,attempt_no,status,result,defect_summary,reprocess_instructions,decision_by,decision_at
              from quality_inspections where production_order_id=? order by attempt_no desc limit 1
            """,orderId);
        out.put("quality",quality==null?Map.of():quality);
        return out;
    }

    @Transactional
    public void setMaterialActual(UUID orderId, UUID requirementId, MaterialActualRequest input, String actor) {
        Map<String,Object> order = one("select id,production_number,status from production_orders where id=? for update", orderId);
        if(order==null) throw new IllegalArgumentException("La orden de producción no existe");
        String status = String.valueOf(order.get("status"));
        if(!Set.of("IN_PROCESS","PAUSED").contains(status)) throw new IllegalArgumentException("El consumo real solo se registra con la OP en proceso o pausada");
        Map<String,Object> req = one("""
            select id,production_order_id,inventory_item_id,planned_quantity_kg,actual_quantity_kg,material_name
              from production_material_requirements where id=? for update
            """, requirementId);
        if(req==null || !orderId.equals(req.get("production_order_id"))) throw new IllegalArgumentException("El material no pertenece a esta OP");
        UUID itemId=(UUID)req.get("inventory_item_id");
        if(itemId==null) throw new IllegalArgumentException("El material no está vinculado a un insumo de almacén");
        BigDecimal next = nonNegative(input==null?null:input.actualQuantityKg(),"El consumo real no puede ser negativo");
        BigDecimal previous = bd(req.get("actual_quantity_kg"));
        BigDecimal delta = next.subtract(previous).setScale(6,RoundingMode.HALF_UP);
        String productionNumber=String.valueOf(order.get("production_number"));
        String comment=clean(input==null?null:input.comment());
        if(delta.compareTo(BigDecimal.ZERO)>0){
            inventory.issueProductionMaterial(orderId, requirementId, itemId, delta, productionNumber, actor, comment);
            insertConsumption(orderId,requirementId,itemId,"ISSUE",delta,comment,actor);
        }else if(delta.compareTo(BigDecimal.ZERO)<0){
            BigDecimal returned=delta.abs();
            inventory.returnProductionMaterial(orderId, itemId, returned, productionNumber, actor, comment);
            insertConsumption(orderId,requirementId,itemId,"RETURN",returned,comment,actor);
        }
        inventory.releaseRequirementReservation(requirementId,actor,"Consumo real confirmado para "+productionNumber);
        BigDecimal planned=bd(req.get("planned_quantity_kg"));
        BigDecimal variance=next.subtract(planned).setScale(6,RoundingMode.HALF_UP);
        jdbc.update("""
            update production_material_requirements
               set actual_quantity_kg=?,consumption_variance_kg=?,consumption_confirmed=true,
                   consumption_confirmed_by=?,consumption_confirmed_at=now(),stock_status='CONSUMED',
                   warehouse_comment=?
             where id=?
            """,next,variance,actor,comment==null?"Consumo real confirmado":comment,requirementId);
        Integer pending=jdbc.queryForObject("select count(*) from production_material_requirements where production_order_id=? and consumption_confirmed=false",Integer.class,orderId);
        if(pending!=null&&pending==0) jdbc.update("update production_orders set material_status='CONSUMED',version_no=version_no+1,updated_at=now() where id=?",orderId);
        else jdbc.update("update production_orders set version_no=version_no+1,updated_at=now() where id=?",orderId);
        event(orderId,"MATERIAL_ACTUAL",status,status,String.valueOf(req.get("material_name"))+": "+next.toPlainString()+" kg",actor);
        audit.record("OP_CONSUMO_REAL","Producción",orderId.toString(),productionNumber+" · "+req.get("material_name")+" · "+next+" kg");
    }

    @Transactional
    public void saveSummary(UUID orderId, ExecutionSummaryRequest input, String actor) {
        Map<String,Object> order=one("select id,production_number,status from production_orders where id=? for update",orderId);
        if(order==null)throw new IllegalArgumentException("La orden de producción no existe");
        String status=String.valueOf(order.get("status"));
        if(!Set.of("IN_PROCESS","PAUSED").contains(status))throw new IllegalArgumentException("Los datos reales solo pueden registrarse con la OP en proceso o pausada");
        BigDecimal output=nullableNonNegative(input==null?null:input.actualOutputWeightKg(),"El peso producido no puede ser negativo",3);
        BigDecimal reprocess=nonNegativeScale(input==null?null:input.reprocessWeightKg(),"El peso de reproceso no puede ser negativo",3);
        BigDecimal waste=nonNegativeScale(input==null?null:input.wasteWeightKg(),"La merma no puede ser negativa",3);
        BigDecimal water=nullableNonNegative(input==null?null:input.waterConsumptionL(),"El consumo de agua no puede ser negativo",3);
        BigDecimal energy=nullableNonNegative(input==null?null:input.energyKwh(),"El consumo de energía no puede ser negativo",3);
        jdbc.update("""
            update production_orders set actual_output_weight_kg=?,reprocess_weight_kg=?,waste_weight_kg=?,
                   water_consumption_l=?,energy_kwh=?,execution_notes=?,version_no=version_no+1,updated_at=now()
             where id=?
            """,output,reprocess,waste,water,energy,clean(input==null?null:input.executionNotes()),orderId);
        event(orderId,"EXECUTION_DATA",status,status,"Datos reales de producción actualizados",actor);
        audit.record("OP_DATOS_REALES","Producción",orderId.toString(),String.valueOf(order.get("production_number"))+" · datos reales actualizados");
    }

    @Transactional
    public void addIncident(UUID orderId, IncidentRequest input, String actor) {
        Map<String,Object> order=one("select id,production_number,status from production_orders where id=? for update",orderId);
        if(order==null)throw new IllegalArgumentException("La orden de producción no existe");
        String status=String.valueOf(order.get("status"));
        if(!Set.of("IN_PROCESS","PAUSED").contains(status))throw new IllegalArgumentException("Las incidencias se registran durante la ejecución de la OP");
        String type=enumValue(input==null?null:input.incidentType(),Set.of("MACHINE","PROCESS","MATERIAL","QUALITY","SAFETY","OTHER"),"Tipo de incidencia no válido","OTHER");
        String severity=enumValue(input==null?null:input.severity(),Set.of("LOW","MEDIUM","HIGH","CRITICAL"),"Severidad no válida","MEDIUM");
        int downtime=input==null||input.downtimeMinutes()==null?0:input.downtimeMinutes();
        if(downtime<0)throw new IllegalArgumentException("Los minutos de parada no pueden ser negativos");
        String description=clean(input==null?null:input.description());
        if(description==null)throw new IllegalArgumentException("Describa la incidencia");
        jdbc.update("""
            insert into production_incidents(production_order_id,incident_type,severity,downtime_minutes,description,corrective_action,actor)
            values (?,?,?,?,?,?,?)
            """,orderId,type,severity,downtime,description,clean(input.correctiveAction()),actor);
        event(orderId,"INCIDENT",status,status,type+" / "+severity+": "+description,actor);
        audit.record("OP_INCIDENCIA","Producción",orderId.toString(),String.valueOf(order.get("production_number"))+" · "+type+" · "+severity);
    }

    public void validateReadyForQuality(UUID orderId){
        Integer total=jdbc.queryForObject("select count(*) from production_material_requirements where production_order_id=?",Integer.class,orderId);
        Integer confirmed=jdbc.queryForObject("select count(*) from production_material_requirements where production_order_id=? and consumption_confirmed=true",Integer.class,orderId);
        if(total==null||total==0)throw new IllegalArgumentException("La OP no tiene materiales calculados");
        if(!Objects.equals(total,confirmed))throw new IllegalArgumentException("Confirme el consumo real de todos los insumos antes de enviar a calidad");
        BigDecimal output=jdbc.queryForObject("select actual_output_weight_kg from production_orders where id=?",BigDecimal.class,orderId);
        if(output==null||output.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("Registre el peso real producido antes de enviar a calidad");
    }

    private void insertConsumption(UUID orderId,UUID reqId,UUID itemId,String kind,BigDecimal qty,String comment,String actor){
        jdbc.update("""
            insert into production_material_consumptions(production_order_id,production_material_requirement_id,inventory_item_id,movement_kind,quantity_kg,comment,actor)
            values (?,?,?,?,?,?,?)
            """,orderId,reqId,itemId,kind,qty,comment,actor);
    }

    private void event(UUID orderId,String type,String from,String to,String comment,String actor){
        jdbc.update("""
            insert into production_events(production_order_id,event_type,from_status,to_status,comment,actor,created_at)
            values (?,?,?,?,?,?,now())
            """,orderId,type,from,to,comment,actor);
    }
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private static BigDecimal bd(Object x){return x==null?BigDecimal.ZERO:(x instanceof BigDecimal b?b:new BigDecimal(x.toString()));}
    private static BigDecimal nonNegative(BigDecimal x,String message){return nonNegativeScale(x,message,6);}
    private static BigDecimal nonNegativeScale(BigDecimal x,String message,int scale){if(x==null)return BigDecimal.ZERO;if(x.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException(message);return x.setScale(scale,RoundingMode.HALF_UP);}
    private static BigDecimal nullableNonNegative(BigDecimal x,String message,int scale){if(x==null)return null;if(x.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException(message);return x.setScale(scale,RoundingMode.HALF_UP);}
    private static String clean(String x){if(x==null)return null;String v=x.trim();return v.isEmpty()?null:v;}
    private static String enumValue(String value,Set<String> valid,String message,String fallback){String v=clean(value);if(v==null)return fallback;v=v.toUpperCase(Locale.ROOT);if(!valid.contains(v))throw new IllegalArgumentException(message);return v;}
}
