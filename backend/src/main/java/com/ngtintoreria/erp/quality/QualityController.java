package com.ngtintoreria.erp.quality;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/quality")
public class QualityController {
    private static final Set<String> CHECK_TYPES=Set.of("COLOR_MATCH","PH","GRAMMAGE_GSM","WIDTH_CM","FASTNESS_WASH","FASTNESS_RUB","SHRINKAGE","HAND_FEEL","STAINS","MOISTURE","OTHER");
    private static final Set<String> CHECK_RESULTS=Set.of("PENDING","PASS","OBSERVED","FAIL");
    private static final Set<String> DECISIONS=Set.of("APPROVED","OBSERVED","REPROCESS","REJECTED");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final QualityStorageService storage;

    public QualityController(JdbcTemplate jdbc,AuditService audit,QualityStorageService storage){this.jdbc=jdbc;this.audit=audit;this.storage=storage;}

    public record CheckRequest(UUID id,String checkType,String parameterName,String targetValue,String actualValue,String unit,String result,String notes){}
    public record InspectionRequest(Integer versionNo,String generalNotes,String defectSummary,List<CheckRequest> checks){}
    public record SubmitRequest(String comment){}
    public record DecisionRequest(String result,String comment,String reprocessInstructions){}

    @GetMapping
    @PreAuthorize("hasAuthority('QUALITY_READ')")
    public List<Map<String,Object>> list(){
        return jdbc.queryForList("""
            select po.id production_order_id,po.production_number,po.batch_number,po.status production_status,
                   po.priority,po.batch_weight_kg,po.actual_output_weight_kg,po.actual_end,
                   c.name client_name,so.order_number,m.code machine_code,m.name machine_name,
                   f.name fabric_name,dc.name color_name,pt.name process_name,
                   qi.id inspection_id,qi.inspection_number,qi.attempt_no,qi.status inspection_status,qi.result,
                   qi.inspected_by,qi.inspected_at,qi.decision_by,qi.decision_at,qi.updated_at
              from production_orders po
              join clients c on c.id=po.client_id
              join sales_orders so on so.id=po.sales_order_id
              join machines m on m.id=po.machine_id
              join formulas fo on fo.id=po.formula_id
              join fabrics f on f.id=fo.fabric_id
              join dye_colors dc on dc.id=fo.color_id
              join process_types pt on pt.id=fo.process_id
              left join lateral (
                select q.* from quality_inspections q where q.production_order_id=po.id
                 order by q.attempt_no desc limit 1
              ) qi on true
             where po.status in ('IN_QUALITY','REPROCESS_REQUIRED','READY_FOR_DISPATCH','PARTIALLY_DISPATCHED','DISPATCHED','DELIVERED','QUALITY_REJECTED')
             order by case po.status when 'IN_QUALITY' then 0 when 'REPROCESS_REQUIRED' then 1 when 'READY_FOR_DISPATCH' then 2 when 'PARTIALLY_DISPATCHED' then 3 when 'DISPATCHED' then 4 when 'DELIVERED' then 5 else 6 end,
                      coalesce(qi.updated_at,po.actual_end,po.updated_at) desc
            """);
    }

    @PostMapping("/orders/{orderId}/inspections")
    @Transactional
    @PreAuthorize("hasAuthority('QUALITY_WRITE')")
    public Map<String,Object> createInspection(@PathVariable UUID orderId,Authentication auth){
        Map<String,Object> order=one("select id,production_number,status from production_orders where id=? for update",orderId);
        if(order==null)throw new IllegalArgumentException("La orden de producción no existe");
        if(!"IN_QUALITY".equals(String.valueOf(order.get("status"))))throw new IllegalArgumentException("La OP debe estar en Calidad para abrir una inspección");
        Map<String,Object> active=one("select id,status,result from quality_inspections where production_order_id=? order by attempt_no desc limit 1",orderId);
        if(active!=null&&!"DECIDED".equals(String.valueOf(active.get("status"))))return detail((UUID)active.get("id"));
        int attempt=Optional.ofNullable(jdbc.queryForObject("select coalesce(max(attempt_no),0)+1 from quality_inspections where production_order_id=?",Integer.class,orderId)).orElse(1);
        String number=jdbc.queryForObject("select 'QC-' || lpad(nextval('quality_inspection_number_seq')::text,6,'0')",String.class);
        UUID id=jdbc.queryForObject("""
            insert into quality_inspections(inspection_number,production_order_id,attempt_no,status,result,created_by)
            values (?,?,?,'DRAFT','PENDING',?) returning id
            """,UUID.class,number,orderId,attempt,auth.getName());
        createDefaultChecks(id,orderId);
        event(id,"CREATED",null,"DRAFT",null,"PENDING","Inspección creada desde "+order.get("production_number"),auth.getName());
        audit.record("CALIDAD_INSPECCION_CREADA","Calidad",id.toString(),number+" · "+order.get("production_number")+" · intento "+attempt);
        return detail(id);
    }

    @GetMapping("/inspections/{id}")
    @PreAuthorize("hasAuthority('QUALITY_READ')")
    public Map<String,Object> get(@PathVariable UUID id){return detail(id);}

    @PutMapping("/inspections/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('QUALITY_WRITE')")
    public Map<String,Object> update(@PathVariable UUID id,@RequestBody InspectionRequest input,Authentication auth){
        Map<String,Object> inspection=one("select * from quality_inspections where id=? for update",id);
        if(inspection==null)throw new IllegalArgumentException("La inspección no existe");
        if(!"DRAFT".equals(String.valueOf(inspection.get("status"))))throw new IllegalArgumentException("Solo una inspección en borrador admite cambios");
        if(input==null||input.versionNo()==null||input.versionNo()!=((Number)inspection.get("version_no")).intValue())throw new IllegalArgumentException("La inspección fue modificada por otro usuario; actualice la pantalla");
        List<CheckRequest> checks=input.checks()==null?List.of():input.checks();
        if(checks.isEmpty())throw new IllegalArgumentException("Registre al menos una prueba de calidad");
        jdbc.update("update quality_inspections set general_notes=?,defect_summary=?,version_no=version_no+1,updated_at=now() where id=?",clean(input.generalNotes()),clean(input.defectSummary()),id);
        jdbc.update("delete from quality_checks where quality_inspection_id=?",id);
        int line=1;
        for(CheckRequest c:checks){
            String type=enumValue(c.checkType(),CHECK_TYPES,"Tipo de prueba no válido","OTHER");
            String parameter=clean(c.parameterName());if(parameter==null)throw new IllegalArgumentException("Indique el nombre del parámetro de calidad");
            String result=enumValue(c.result(),CHECK_RESULTS,"Resultado de prueba no válido","PENDING");
            jdbc.update("""
                insert into quality_checks(quality_inspection_id,line_no,check_type,parameter_name,target_value,actual_value,unit,result,notes)
                values (?,?,?,?,?,?,?,?,?)
                """,id,line++,type,parameter,clean(c.targetValue()),clean(c.actualValue()),clean(c.unit()),result,clean(c.notes()));
        }
        event(id,"UPDATED","DRAFT","DRAFT","PENDING","PENDING","Pruebas y observaciones actualizadas",auth.getName());
        audit.record("CALIDAD_INSPECCION_ACTUALIZADA","Calidad",id.toString(),String.valueOf(inspection.get("inspection_number"))+" · pruebas actualizadas");
        return detail(id);
    }

    @PostMapping("/inspections/{id}/submit")
    @Transactional
    @PreAuthorize("hasAuthority('QUALITY_WRITE')")
    public Map<String,Object> submit(@PathVariable UUID id,@RequestBody(required=false) SubmitRequest input,Authentication auth){
        Map<String,Object> inspection=one("select * from quality_inspections where id=? for update",id);
        if(inspection==null)throw new IllegalArgumentException("La inspección no existe");
        if(!"DRAFT".equals(String.valueOf(inspection.get("status"))))throw new IllegalArgumentException("La inspección ya fue enviada para decisión");
        Integer total=jdbc.queryForObject("select count(*) from quality_checks where quality_inspection_id=?",Integer.class,id);
        Integer pending=jdbc.queryForObject("select count(*) from quality_checks where quality_inspection_id=? and result='PENDING'",Integer.class,id);
        if(total==null||total==0)throw new IllegalArgumentException("Registre pruebas antes de enviar la inspección");
        if(pending!=null&&pending>0)throw new IllegalArgumentException("Todas las pruebas deben tener resultado antes de enviar la inspección");
        jdbc.update("update quality_inspections set status='SUBMITTED',inspected_by=?,inspected_at=now(),version_no=version_no+1,updated_at=now() where id=?",auth.getName(),id);
        event(id,"SUBMITTED","DRAFT","SUBMITTED","PENDING","PENDING",clean(input==null?null:input.comment()),auth.getName());
        audit.record("CALIDAD_INSPECCION_ENVIADA","Calidad",id.toString(),String.valueOf(inspection.get("inspection_number"))+" · enviada para decisión");
        return detail(id);
    }

    @PostMapping("/inspections/{id}/decision")
    @Transactional
    @PreAuthorize("hasAuthority('QUALITY_DECIDE')")
    public Map<String,Object> decision(@PathVariable UUID id,@RequestBody DecisionRequest input,Authentication auth){
        Map<String,Object> inspection=one("select * from quality_inspections where id=? for update",id);
        if(inspection==null)throw new IllegalArgumentException("La inspección no existe");
        if(!"SUBMITTED".equals(String.valueOf(inspection.get("status"))))throw new IllegalArgumentException("La inspección debe estar enviada antes de registrar una decisión");
        String result=enumValue(input==null?null:input.result(),DECISIONS,"Decisión de calidad no válida",null);
        if(result==null)throw new IllegalArgumentException("Seleccione la decisión de Calidad");
        String comment=clean(input==null?null:input.comment());
        String reprocess=clean(input==null?null:input.reprocessInstructions());
        Integer pending=jdbc.queryForObject("select count(*) from quality_checks where quality_inspection_id=? and result='PENDING'",Integer.class,id);
        if(pending!=null&&pending>0)throw new IllegalArgumentException("Existen pruebas pendientes");
        if("APPROVED".equals(result)){
            Integer fails=jdbc.queryForObject("select count(*) from quality_checks where quality_inspection_id=? and result='FAIL'",Integer.class,id);
            if(fails!=null&&fails>0)throw new IllegalArgumentException("No se puede aprobar una inspección con pruebas fallidas");
        }
        if("REPROCESS".equals(result)&&reprocess==null)throw new IllegalArgumentException("Indique las instrucciones de reproceso");
        if("REJECTED".equals(result)&&comment==null)throw new IllegalArgumentException("Indique el motivo del rechazo");
        UUID orderId=(UUID)inspection.get("production_order_id");
        Map<String,Object> order=one("select id,production_number,status from production_orders where id=? for update",orderId);
        if(order==null||!"IN_QUALITY".equals(String.valueOf(order.get("status"))))throw new IllegalArgumentException("La OP ya no se encuentra disponible para decisión de Calidad");
        String nextOrderStatus=switch(result){case "APPROVED"->"READY_FOR_DISPATCH";case "OBSERVED"->"IN_QUALITY";case "REPROCESS"->"REPROCESS_REQUIRED";case "REJECTED"->"QUALITY_REJECTED";default->throw new IllegalArgumentException("Decisión no válida");};
        jdbc.update("""
            update quality_inspections set status='DECIDED',result=?,decision_by=?,decision_at=now(),
                   reprocess_instructions=?,version_no=version_no+1,updated_at=now() where id=?
            """,result,auth.getName(),reprocess,id);
        jdbc.update("update production_orders set status=?,version_no=version_no+1,updated_at=now() where id=?",nextOrderStatus,orderId);
        event(id,"DECISION","SUBMITTED","DECIDED","PENDING",result,comment,auth.getName());
        jdbc.update("""
            insert into production_events(production_order_id,event_type,from_status,to_status,comment,actor,created_at)
            values (?,'QUALITY_DECISION','IN_QUALITY',?,?,?,now())
            """,orderId,nextOrderStatus,"Calidad: "+result+(comment==null?"":" · "+comment),auth.getName());
        audit.record("CALIDAD_DECISION","Calidad",id.toString(),inspection.get("inspection_number")+" · "+order.get("production_number")+" · "+result);
        return detail(id);
    }

    @PostMapping(value="/inspections/{id}/evidences",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    @PreAuthorize("hasAuthority('QUALITY_EVIDENCE')")
    public Map<String,Object> upload(@PathVariable UUID id,@RequestPart("file") MultipartFile file,@RequestParam(required=false) String note,Authentication auth){
        Map<String,Object> inspection=one("select id,inspection_number,status from quality_inspections where id=?",id);
        if(inspection==null)throw new IllegalArgumentException("La inspección no existe");
        if("DECIDED".equals(String.valueOf(inspection.get("status"))))throw new IllegalArgumentException("La inspección decidida ya no admite nuevas evidencias");
        QualityStorageService.StoredFile saved=storage.save(file);
        try{
            jdbc.update("""
                insert into quality_evidences(quality_inspection_id,original_name,storage_name,content_type,size_bytes,note,uploaded_by)
                values (?,?,?,?,?,?,?)
                """,id,saved.originalName(),saved.storageName(),saved.contentType(),saved.sizeBytes(),clean(note),auth.getName());
        }catch(RuntimeException e){storage.delete(saved.storageName());throw e;}
        event(id,"EVIDENCE",String.valueOf(inspection.get("status")),String.valueOf(inspection.get("status")),null,null,"Evidencia adjuntada: "+saved.originalName(),auth.getName());
        audit.record("CALIDAD_EVIDENCIA","Calidad",id.toString(),inspection.get("inspection_number")+" · "+saved.originalName());
        return detail(id);
    }

    @GetMapping("/evidences/{evidenceId}")
    @PreAuthorize("hasAuthority('QUALITY_READ')")
    public ResponseEntity<Resource> evidence(@PathVariable UUID evidenceId){
        Map<String,Object> e=one("select * from quality_evidences where id=?",evidenceId);
        if(e==null)throw new IllegalArgumentException("La evidencia no existe");
        Resource resource=storage.load(String.valueOf(e.get("storage_name")));
        ContentDisposition cd=ContentDisposition.attachment().filename(String.valueOf(e.get("original_name")),StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(String.valueOf(e.get("content_type"))))
                .header(HttpHeaders.CONTENT_DISPOSITION,cd.toString()).body(resource);
    }

    @DeleteMapping("/evidences/{evidenceId}")
    @Transactional
    @PreAuthorize("hasAuthority('QUALITY_EVIDENCE')")
    public void deleteEvidence(@PathVariable UUID evidenceId,Authentication auth){
        Map<String,Object> e=one("select qe.*,qi.status inspection_status,qi.inspection_number from quality_evidences qe join quality_inspections qi on qi.id=qe.quality_inspection_id where qe.id=? for update",evidenceId);
        if(e==null)throw new IllegalArgumentException("La evidencia no existe");
        if("DECIDED".equals(String.valueOf(e.get("inspection_status"))))throw new IllegalArgumentException("No se puede eliminar evidencia de una inspección decidida");
        jdbc.update("delete from quality_evidences where id=?",evidenceId);
        storage.delete(String.valueOf(e.get("storage_name")));
        audit.record("CALIDAD_EVIDENCIA_ELIMINADA","Calidad",String.valueOf(e.get("quality_inspection_id")),e.get("inspection_number")+" · "+e.get("original_name")+" · "+auth.getName());
    }

    private Map<String,Object> detail(UUID id){
        Map<String,Object> header=one("""
            select qi.id,qi.inspection_number,qi.production_order_id,qi.attempt_no,qi.status,qi.result,
                   qi.inspected_by,qi.inspected_at,qi.decision_by,qi.decision_at,qi.general_notes,qi.defect_summary,
                   qi.reprocess_instructions,qi.version_no,qi.created_by,qi.created_at,qi.updated_at,
                   po.production_number,po.batch_number,po.status production_status,po.priority,po.batch_weight_kg,
                   po.actual_output_weight_kg,po.reprocess_weight_kg,po.waste_weight_kg,po.actual_start,po.actual_end,
                   c.name client_name,so.order_number,m.code machine_code,m.name machine_name,
                   f.name fabric_name,f.gsm target_gsm,f.width_cm target_width_cm,
                   dc.name color_name,dc.pantone,fv.ph target_ph,pt.name process_name,ft.name finish_name
              from quality_inspections qi join production_orders po on po.id=qi.production_order_id
              join clients c on c.id=po.client_id join sales_orders so on so.id=po.sales_order_id join machines m on m.id=po.machine_id
              join formulas fo on fo.id=po.formula_id join formula_versions fv on fv.id=po.formula_version_id
              join fabrics f on f.id=fo.fabric_id join dye_colors dc on dc.id=fo.color_id join process_types pt on pt.id=fo.process_id
              left join finish_types ft on ft.id=fo.finish_id where qi.id=?
            """,id);
        if(header==null)throw new IllegalArgumentException("La inspección no existe");
        Map<String,Object> out=new LinkedHashMap<>();out.put("header",header);
        out.put("checks",jdbc.queryForList("select id,line_no,check_type,parameter_name,target_value,actual_value,unit,result,notes,created_at,updated_at from quality_checks where quality_inspection_id=? order by line_no",id));
        out.put("evidences",jdbc.queryForList("select id,original_name,content_type,size_bytes,note,uploaded_by,created_at from quality_evidences where quality_inspection_id=? order by created_at desc",id));
        out.put("events",jdbc.queryForList("select id,event_type,from_status,to_status,from_result,to_result,comment,actor,created_at from quality_events where quality_inspection_id=? order by created_at,id",id));
        return out;
    }

    private void createDefaultChecks(UUID inspectionId,UUID orderId){
        Map<String,Object> defaults=one("""
            select dc.name color_name,dc.pantone,fv.ph target_ph,f.gsm target_gsm,f.width_cm target_width_cm
              from production_orders po join formulas fo on fo.id=po.formula_id join formula_versions fv on fv.id=po.formula_version_id
              join fabrics f on f.id=fo.fabric_id join dye_colors dc on dc.id=fo.color_id where po.id=?
            """,orderId);
        if(defaults==null)return;
        List<Object[]> rows=new ArrayList<>();
        rows.add(new Object[]{"COLOR_MATCH","Igualación de color",joinColor(defaults),"Visual / ΔE"});
        if(defaults.get("target_ph")!=null)rows.add(new Object[]{"PH","pH final",String.valueOf(defaults.get("target_ph")),"pH"});
        if(defaults.get("target_gsm")!=null)rows.add(new Object[]{"GRAMMAGE_GSM","Gramaje",String.valueOf(defaults.get("target_gsm")),"g/m²"});
        if(defaults.get("target_width_cm")!=null)rows.add(new Object[]{"WIDTH_CM","Ancho",String.valueOf(defaults.get("target_width_cm")),"cm"});
        rows.add(new Object[]{"STAINS","Manchas / contaminación","Sin defectos visibles","Visual"});
        rows.add(new Object[]{"HAND_FEEL","Tacto / acabado","Conforme a muestra aprobada","Visual / tacto"});
        int line=1;for(Object[] r:rows)jdbc.update("insert into quality_checks(quality_inspection_id,line_no,check_type,parameter_name,target_value,unit) values (?,?,?,?,?,?)",inspectionId,line++,r[0],r[1],r[2],r[3]);
    }

    private String joinColor(Map<String,Object> m){String color=String.valueOf(m.get("color_name"));Object pantone=m.get("pantone");return pantone==null||String.valueOf(pantone).isBlank()?color:color+" · Pantone "+pantone;}
    private void event(UUID id,String type,String fromStatus,String toStatus,String fromResult,String toResult,String comment,String actor){
        jdbc.update("""
            insert into quality_events(quality_inspection_id,event_type,from_status,to_status,from_result,to_result,comment,actor)
            values (?,?,?,?,?,?,?,?)
            """,id,type,fromStatus,toStatus,fromResult,toResult,clean(comment),actor);
    }
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private static String clean(String x){if(x==null)return null;String v=x.trim();return v.isEmpty()?null:v;}
    private static String enumValue(String value,Set<String> valid,String message,String fallback){String v=clean(value);if(v==null)return fallback;v=v.toUpperCase(Locale.ROOT);if(!valid.contains(v))throw new IllegalArgumentException(message);return v;}
}
