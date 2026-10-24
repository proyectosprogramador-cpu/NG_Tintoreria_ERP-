package com.ngtintoreria.erp.maintenance;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {
    private static final Set<String> TYPES=Set.of("PREVENTIVE","CORRECTIVE","EMERGENCY","INSPECTION");
    private static final Set<String> PRIORITIES=Set.of("LOW","NORMAL","HIGH","CRITICAL");
    private static final Set<String> TRIGGERS=Set.of("MANUAL","HOURS","DAYS","INCIDENT");
    private static final Set<String> FINAL_STATUSES=Set.of("AVAILABLE","OUT_OF_SERVICE");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final MaintenanceStorageService storage;
    private final MaintenanceAvailabilityService availability;

    public MaintenanceController(JdbcTemplate jdbc,AuditService audit,MaintenanceStorageService storage,MaintenanceAvailabilityService availability){
        this.jdbc=jdbc;this.audit=audit;this.storage=storage;this.availability=availability;
    }

    public record MachineProfileRequest(String brand,String model,String serialNumber,String location,LocalDate commissioningDate,
                                        BigDecimal currentMeterHours,BigDecimal maintenanceIntervalHours,Integer maintenanceIntervalDays,
                                        String operationalStatus){}
    public record WorkOrderRequest(UUID machineId,String maintenanceType,String priority,String triggerSource,String title,String description,
                                   OffsetDateTime scheduledStart,OffsetDateTime scheduledEnd,String technicianName,String providerName){}
    public record CompleteRequest(String workPerformed,String partsUsed,Integer downtimeMinutes,BigDecimal laborCost,BigDecimal partsCost,
                                  BigDecimal externalCost,BigDecimal meterHoursEnd,String finalMachineStatus){}
    public record CancelRequest(String reason){}

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('MAINTENANCE_READ') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> summary(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("machines",one("""
            select count(*) total,
                   count(*) filter(where active and coalesce(operational_status,'AVAILABLE')='AVAILABLE') available,
                   count(*) filter(where coalesce(operational_status,'AVAILABLE')='IN_MAINTENANCE') in_maintenance,
                   count(*) filter(where coalesce(operational_status,'AVAILABLE')='OUT_OF_SERVICE') out_of_service,
                   count(*) filter(where coalesce(operational_status,'AVAILABLE')='RETIRED') retired,
                   count(*) filter(where active and ((next_maintenance_at is not null and next_maintenance_at<now()) or
                         (next_maintenance_meter_hours is not null and current_meter_hours>=next_maintenance_meter_hours))) overdue
              from machines
            """));
        out.put("workOrders",one("""
            select count(*) filter(where status='PLANNED') planned,
                   count(*) filter(where status='IN_PROGRESS') in_progress,
                   count(*) filter(where status='COMPLETED' and coalesce(actual_end,updated_at)>=date_trunc('month',now())) completed_month,
                   coalesce(sum(total_cost) filter(where status='COMPLETED' and coalesce(actual_end,updated_at)>=date_trunc('month',now())),0) cost_month,
                   coalesce(sum(downtime_minutes) filter(where status='COMPLETED' and coalesce(actual_end,updated_at)>=date_trunc('month',now())),0) downtime_month
              from maintenance_work_orders
            """));
        out.put("upcoming",jdbc.queryForList("""
            select mw.id,mw.maintenance_number,mw.maintenance_type,mw.priority,mw.title,mw.scheduled_start,mw.scheduled_end,
                   m.code machine_code,m.name machine_name
              from maintenance_work_orders mw join machines m on m.id=mw.machine_id
             where mw.status='PLANNED' and mw.scheduled_end>=now()-interval '1 day'
             order by mw.scheduled_start limit 8
            """));
        return out;
    }

    @GetMapping("/machines")
    @PreAuthorize("hasAuthority('MAINTENANCE_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> machines(){
        return jdbc.queryForList("""
            select m.id,m.code,m.name,m.active,m.operational_status,m.min_capacity_kg,m.max_capacity_kg,m.cost_per_hour,
                   m.brand,m.model,m.serial_number,m.location,m.commissioning_date,m.current_meter_hours,
                   m.maintenance_interval_hours,m.maintenance_interval_days,m.last_maintenance_at,m.last_maintenance_meter_hours,
                   m.next_maintenance_at,m.next_maintenance_meter_hours,
                   case
                     when not m.active or coalesce(m.operational_status,'AVAILABLE')='RETIRED' then 'RETIRED'
                     when coalesce(m.operational_status,'AVAILABLE')='OUT_OF_SERVICE' then 'OUT_OF_SERVICE'
                     when coalesce(m.operational_status,'AVAILABLE')='IN_MAINTENANCE' then 'IN_MAINTENANCE'
                     when (m.next_maintenance_at is not null and m.next_maintenance_at<now()) or
                          (m.next_maintenance_meter_hours is not null and m.current_meter_hours>=m.next_maintenance_meter_hours) then 'OVERDUE'
                     when (m.next_maintenance_at is not null and m.next_maintenance_at<=now()+interval '7 days') or
                          (m.next_maintenance_meter_hours is not null and m.current_meter_hours>=m.next_maintenance_meter_hours-25) then 'DUE_SOON'
                     else 'OK' end maintenance_health,
                   (select count(*) from maintenance_work_orders w where w.machine_id=m.id and w.status in ('PLANNED','IN_PROGRESS')) open_work_orders
              from machines m order by lower(m.name)
            """);
    }

    @PutMapping("/machines/{id}/profile")
    @Transactional
    @PreAuthorize("hasAuthority('MAINTENANCE_CONFIG') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> updateMachine(@PathVariable UUID id,@RequestBody MachineProfileRequest input,Authentication auth){
        Map<String,Object> current=required("select * from machines where id=? for update",id);
        BigDecimal meter=nonNegative(input.currentMeterHours(),bd(current.get("current_meter_hours")),"El horómetro no puede ser negativo");
        BigDecimal intervalHours=positiveOrNull(input.maintenanceIntervalHours(),"El intervalo por horas debe ser mayor que cero");
        Integer intervalDays=input.maintenanceIntervalDays();if(intervalDays!=null&&intervalDays<=0)throw new IllegalArgumentException("El intervalo por días debe ser mayor que cero");
        String status=upper(input.operationalStatus());if(status.isBlank())status=String.valueOf(current.getOrDefault("operational_status","AVAILABLE"));
        if(!Set.of("AVAILABLE","OUT_OF_SERVICE","RETIRED").contains(status))throw new IllegalArgumentException("Estado de máquina inválido para configuración");
        Integer inProgress=jdbc.queryForObject("select count(*) from maintenance_work_orders where machine_id=? and status='IN_PROGRESS'",Integer.class,id);
        if(inProgress!=null&&inProgress>0&&!"OUT_OF_SERVICE".equals(status))throw new IllegalArgumentException("La máquina tiene un mantenimiento en curso; finalícelo antes de cambiar su estado");
        OffsetDateTime lastAt=asOffset(current.get("last_maintenance_at"));BigDecimal lastMeter=bd(current.get("last_maintenance_meter_hours"));
        OffsetDateTime nextAt=lastAt!=null&&intervalDays!=null?lastAt.plusDays(intervalDays):null;
        BigDecimal nextMeter=lastMeter!=null&&intervalHours!=null?lastMeter.add(intervalHours):null;
        jdbc.update("""
            update machines set brand=?,model=?,serial_number=?,location=?,commissioning_date=?,current_meter_hours=?,
                   maintenance_interval_hours=?,maintenance_interval_days=?,operational_status=?,next_maintenance_at=?,next_maintenance_meter_hours=?,updated_at=now()
             where id=?
            """,clean(input.brand()),clean(input.model()),clean(input.serialNumber()),clean(input.location()),input.commissioningDate(),meter,
                intervalHours,intervalDays,status,nextAt,nextMeter,id);
        audit.record("MAINTENANCE_MACHINE_PROFILE","Mantenimiento",id.toString(),current.get("code")+" · horómetro "+meter+" h · estado "+status);
        return required("select * from machines where id=?",id);
    }

    @GetMapping("/work-orders")
    @PreAuthorize("hasAuthority('MAINTENANCE_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> workOrders(@RequestParam(required=false) String status,@RequestParam(required=false) String type,@RequestParam(required=false) UUID machineId){
        StringBuilder sql=new StringBuilder("""
            select mw.*,m.code machine_code,m.name machine_name,m.operational_status machine_status
              from maintenance_work_orders mw join machines m on m.id=mw.machine_id where 1=1
            """);List<Object> args=new ArrayList<>();
        if(status!=null&&!status.isBlank()){sql.append(" and mw.status=?");args.add(upper(status));}
        if(type!=null&&!type.isBlank()){String t=upper(type);require(TYPES,t,"Tipo de mantenimiento inválido");sql.append(" and mw.maintenance_type=?");args.add(t);}
        if(machineId!=null){sql.append(" and mw.machine_id=?");args.add(machineId);}
        sql.append(" order by case mw.status when 'IN_PROGRESS' then 0 when 'PLANNED' then 1 when 'COMPLETED' then 2 else 3 end,mw.scheduled_start desc");
        return jdbc.queryForList(sql.toString(),args.toArray());
    }

    @GetMapping("/work-orders/{id}")
    @PreAuthorize("hasAuthority('MAINTENANCE_READ') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> workOrder(@PathVariable UUID id){return detail(id);}

    @PostMapping("/work-orders")
    @Transactional
    @PreAuthorize("hasAuthority('MAINTENANCE_WRITE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> create(@RequestBody WorkOrderRequest input,Authentication auth){
        validateRequest(input);Map<String,Object> machine=required("select id,code,name,active,current_meter_hours,operational_status from machines where id=?",input.machineId());
        if(Boolean.FALSE.equals(machine.get("active")))throw new IllegalArgumentException("La máquina está inactiva");
        if("RETIRED".equalsIgnoreCase(String.valueOf(machine.get("operational_status"))))throw new IllegalArgumentException("La máquina está retirada y no admite nuevas órdenes de mantenimiento");
        availability.assertNoProductionConflict(input.machineId(),input.scheduledStart(),input.scheduledEnd(),null);
        availability.assertNoMaintenanceConflict(input.machineId(),input.scheduledStart(),input.scheduledEnd());
        Long seq=jdbc.queryForObject("select nextval('maintenance_work_order_seq')",Long.class);String number=String.format("MNT-%06d",seq==null?1:seq);
        UUID id=UUID.randomUUID();String type=upper(input.maintenanceType()),priority=upperDefault(input.priority(),"NORMAL"),trigger=upperDefault(input.triggerSource(),"MANUAL");
        jdbc.update("""
            insert into maintenance_work_orders(id,maintenance_number,machine_id,maintenance_type,status,priority,trigger_source,title,description,
              scheduled_start,scheduled_end,technician_name,provider_name,created_by,updated_by)
            values(?,?,?,?, 'PLANNED',?,?,?,?,?,?,?,?,?,?)
            """,id,number,input.machineId(),type,priority,trigger,text(input.title(),220,true),clean(input.description()),input.scheduledStart(),input.scheduledEnd(),
                clean(input.technicianName()),clean(input.providerName()),auth.getName(),auth.getName());
        audit.record("MANTENIMIENTO_PROGRAMADO","Mantenimiento",number,machine.get("code")+" · "+type+" · "+input.scheduledStart());
        return detail(id);
    }

    @PutMapping("/work-orders/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('MAINTENANCE_WRITE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> update(@PathVariable UUID id,@RequestBody WorkOrderRequest input,Authentication auth){
        Map<String,Object> current=required("select * from maintenance_work_orders where id=? for update",id);
        if(!"PLANNED".equals(String.valueOf(current.get("status"))))throw new IllegalArgumentException("Solo un mantenimiento planificado puede editarse");
        validateRequest(input);
        Map<String,Object> targetMachine=required("select id,active,operational_status from machines where id=?",input.machineId());
        if(Boolean.FALSE.equals(targetMachine.get("active"))||"RETIRED".equalsIgnoreCase(String.valueOf(targetMachine.get("operational_status"))))throw new IllegalArgumentException("La máquina seleccionada está inactiva o retirada");
        availability.assertNoProductionConflict(input.machineId(),input.scheduledStart(),input.scheduledEnd(),null);
        // Excluye la propia orden en la comprobación de mantenimiento.
        List<Map<String,Object>> overlap=jdbc.queryForList("""
            select maintenance_number from maintenance_work_orders where machine_id=? and id<>? and status in ('PLANNED','IN_PROGRESS')
             and scheduled_start<? and scheduled_end>? limit 1
            """,input.machineId(),id,input.scheduledEnd(),input.scheduledStart());
        if(!overlap.isEmpty())throw new IllegalArgumentException("Existe otro mantenimiento superpuesto: "+overlap.get(0).get("maintenance_number"));
        jdbc.update("""
            update maintenance_work_orders set machine_id=?,maintenance_type=?,priority=?,trigger_source=?,title=?,description=?,scheduled_start=?,scheduled_end=?,
              technician_name=?,provider_name=?,updated_by=?,updated_at=now() where id=?
            """,input.machineId(),upper(input.maintenanceType()),upperDefault(input.priority(),"NORMAL"),upperDefault(input.triggerSource(),"MANUAL"),
                text(input.title(),220,true),clean(input.description()),input.scheduledStart(),input.scheduledEnd(),clean(input.technicianName()),clean(input.providerName()),auth.getName(),id);
        audit.record("MANTENIMIENTO_REPROGRAMADO","Mantenimiento",String.valueOf(current.get("maintenance_number")),String.valueOf(input.scheduledStart()));
        return detail(id);
    }

    @PostMapping("/work-orders/{id}/start")
    @Transactional
    @PreAuthorize("hasAuthority('MAINTENANCE_EXECUTE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> start(@PathVariable UUID id,Authentication auth){
        Map<String,Object> work=required("select * from maintenance_work_orders where id=? for update",id);
        if(!"PLANNED".equals(String.valueOf(work.get("status"))))throw new IllegalArgumentException("Solo una orden planificada puede iniciarse");
        UUID machineId=uuid(work.get("machine_id"));Map<String,Object> machine=required("select * from machines where id=? for update",machineId);
        Integer running=jdbc.queryForObject("select count(*) from production_orders where machine_id=? and status in ('IN_PROCESS','PAUSED')",Integer.class,machineId);
        if(running!=null&&running>0)throw new IllegalArgumentException("La máquina tiene una producción en proceso/pausa. No se puede iniciar mantenimiento");
        OffsetDateTime now=OffsetDateTime.now();OffsetDateTime plannedEnd=asOffset(work.get("scheduled_end"));
        if(plannedEnd==null||!plannedEnd.isAfter(now))plannedEnd=now.plusHours(1);
        availability.assertNoProductionConflict(machineId,now,plannedEnd,null);
        if("RETIRED".equals(String.valueOf(machine.get("operational_status"))))throw new IllegalArgumentException("La máquina está retirada");
        jdbc.update("update maintenance_work_orders set status='IN_PROGRESS',actual_start=now(),meter_hours_start=?,updated_by=?,updated_at=now() where id=?",
                bd(machine.get("current_meter_hours")),auth.getName(),id);
        jdbc.update("update machines set operational_status='IN_MAINTENANCE',updated_at=now() where id=?",machineId);
        audit.record("MANTENIMIENTO_INICIADO","Mantenimiento",String.valueOf(work.get("maintenance_number")),machine.get("code")+" · "+auth.getName());
        return detail(id);
    }

    @PostMapping("/work-orders/{id}/complete")
    @Transactional
    @PreAuthorize("hasAuthority('MAINTENANCE_EXECUTE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> complete(@PathVariable UUID id,@RequestBody CompleteRequest input,Authentication auth){
        Map<String,Object> work=required("select * from maintenance_work_orders where id=? for update",id);
        if(!"IN_PROGRESS".equals(String.valueOf(work.get("status"))))throw new IllegalArgumentException("El mantenimiento debe estar EN PROGRESO");
        UUID machineId=uuid(work.get("machine_id"));Map<String,Object> machine=required("select * from machines where id=? for update",machineId);
        String performed=text(input.workPerformed(),8000,true);String finalStatus=upperDefault(input.finalMachineStatus(),"AVAILABLE");require(FINAL_STATUSES,finalStatus,"Estado final de máquina inválido");
        BigDecimal startMeter=bd(work.get("meter_hours_start"));BigDecimal meterEnd=nonNegative(input.meterHoursEnd(),bd(machine.get("current_meter_hours")),"El horómetro final no puede ser negativo");
        if(startMeter!=null&&meterEnd.compareTo(startMeter)<0)throw new IllegalArgumentException("El horómetro final no puede ser menor al registrado al iniciar");
        int downtime=input.downtimeMinutes()==null?0:input.downtimeMinutes();if(downtime<0)throw new IllegalArgumentException("Los minutos de parada no pueden ser negativos");
        BigDecimal labor=nonNegative(input.laborCost(),BigDecimal.ZERO,"Costo de mano de obra inválido");BigDecimal parts=nonNegative(input.partsCost(),BigDecimal.ZERO,"Costo de repuestos inválido");BigDecimal external=nonNegative(input.externalCost(),BigDecimal.ZERO,"Costo externo inválido");BigDecimal total=labor.add(parts).add(external);
        Integer days=asInt(machine.get("maintenance_interval_days"));BigDecimal intervalHours=bd(machine.get("maintenance_interval_hours"));OffsetDateTime now=OffsetDateTime.now();
        OffsetDateTime nextAt=days==null?null:now.plusDays(days);BigDecimal nextMeter=intervalHours==null?null:meterEnd.add(intervalHours);
        jdbc.update("""
            update maintenance_work_orders set status='COMPLETED',actual_end=?,meter_hours_end=?,work_performed=?,parts_used=?,downtime_minutes=?,
             labor_cost=?,parts_cost=?,external_cost=?,total_cost=?,final_machine_status=?,next_due_at=?,next_due_meter_hours=?,completed_by=?,updated_by=?,updated_at=now()
             where id=?
            """,now,meterEnd,performed,clean(input.partsUsed()),downtime,labor,parts,external,total,finalStatus,nextAt,nextMeter,auth.getName(),auth.getName(),id);
        jdbc.update("""
            update machines set operational_status=?,current_meter_hours=?,last_maintenance_at=?,last_maintenance_meter_hours=?,
             next_maintenance_at=?,next_maintenance_meter_hours=?,updated_at=now() where id=?
            """,finalStatus,meterEnd,now,meterEnd,nextAt,nextMeter,machineId);
        audit.record("MANTENIMIENTO_COMPLETADO","Mantenimiento",String.valueOf(work.get("maintenance_number")),machine.get("code")+" · costo S/ "+total+" · estado "+finalStatus);
        return detail(id);
    }

    @PostMapping("/work-orders/{id}/cancel")
    @Transactional
    @PreAuthorize("hasAuthority('MAINTENANCE_EXECUTE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> cancel(@PathVariable UUID id,@RequestBody CancelRequest input,Authentication auth){
        Map<String,Object> work=required("select * from maintenance_work_orders where id=? for update",id);
        if(!"PLANNED".equals(String.valueOf(work.get("status"))))throw new IllegalArgumentException("Solo un mantenimiento planificado puede cancelarse");
        String reason=text(input==null?null:input.reason(),500,true);
        jdbc.update("update maintenance_work_orders set status='CANCELLED',cancellation_reason=?,updated_by=?,updated_at=now() where id=?",reason,auth.getName(),id);
        audit.record("MANTENIMIENTO_CANCELADO","Mantenimiento",String.valueOf(work.get("maintenance_number")),reason);
        return detail(id);
    }

    @PostMapping(value="/work-orders/{id}/evidences",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    @PreAuthorize("hasAuthority('MAINTENANCE_WRITE') or hasAuthority('MAINTENANCE_EXECUTE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> upload(@PathVariable UUID id,@RequestPart("file") MultipartFile file,@RequestParam(required=false) String note,Authentication auth){
        Map<String,Object> work=required("select id,maintenance_number,status from maintenance_work_orders where id=?",id);
        if("CANCELLED".equals(String.valueOf(work.get("status"))))throw new IllegalArgumentException("No se pueden adjuntar evidencias a una orden cancelada");
        MaintenanceStorageService.StoredFile saved=storage.save(file);
        try{jdbc.update("""
            insert into maintenance_evidences(maintenance_work_order_id,original_name,storage_name,content_type,size_bytes,note,uploaded_by)
            values(?,?,?,?,?,?,?)
            """,id,saved.originalName(),saved.storageName(),saved.contentType(),saved.sizeBytes(),clean(note),auth.getName());}
        catch(RuntimeException e){storage.delete(saved.storageName());throw e;}
        audit.record("MANTENIMIENTO_EVIDENCIA","Mantenimiento",String.valueOf(work.get("maintenance_number")),saved.originalName());
        return detail(id);
    }

    @GetMapping("/evidences/{evidenceId}")
    @PreAuthorize("hasAuthority('MAINTENANCE_READ') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Resource> evidence(@PathVariable UUID evidenceId){
        Map<String,Object> e=required("select * from maintenance_evidences where id=?",evidenceId);Resource resource=storage.load(String.valueOf(e.get("storage_name")));
        ContentDisposition cd=ContentDisposition.attachment().filename(String.valueOf(e.get("original_name")),StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(String.valueOf(e.get("content_type")))).header(HttpHeaders.CONTENT_DISPOSITION,cd.toString()).body(resource);
    }

    @DeleteMapping("/evidences/{evidenceId}")
    @Transactional
    @PreAuthorize("hasAuthority('MAINTENANCE_WRITE') or hasAuthority('MAINTENANCE_EXECUTE') or hasAuthority('ROLE_ADMIN')")
    public void deleteEvidence(@PathVariable UUID evidenceId,Authentication auth){
        Map<String,Object> e=required("""
            select me.*,mw.status,mw.maintenance_number from maintenance_evidences me join maintenance_work_orders mw on mw.id=me.maintenance_work_order_id where me.id=?
            """,evidenceId);
        if("COMPLETED".equals(String.valueOf(e.get("status"))))throw new IllegalArgumentException("No se elimina evidencia de un mantenimiento completado");
        jdbc.update("delete from maintenance_evidences where id=?",evidenceId);storage.delete(String.valueOf(e.get("storage_name")));
        audit.record("MANTENIMIENTO_EVIDENCIA_ELIMINADA","Mantenimiento",String.valueOf(e.get("maintenance_number")),String.valueOf(e.get("original_name"))+" · "+auth.getName());
    }

    private Map<String,Object> detail(UUID id){
        Map<String,Object> header=required("""
            select mw.*,m.code machine_code,m.name machine_name,m.brand,m.model,m.serial_number,m.location,m.operational_status machine_status,
                   m.current_meter_hours,m.maintenance_interval_hours,m.maintenance_interval_days,m.next_maintenance_at,m.next_maintenance_meter_hours
              from maintenance_work_orders mw join machines m on m.id=mw.machine_id where mw.id=?
            """,id);
        Map<String,Object> out=new LinkedHashMap<>();out.put("header",header);
        out.put("evidences",jdbc.queryForList("select id,original_name,content_type,size_bytes,note,uploaded_by,created_at from maintenance_evidences where maintenance_work_order_id=? order by created_at desc",id));
        return out;
    }

    private void validateRequest(WorkOrderRequest input){
        if(input==null||input.machineId()==null)throw new IllegalArgumentException("Seleccione una máquina");
        String type=upper(input.maintenanceType());require(TYPES,type,"Tipo de mantenimiento inválido");
        require(PRIORITIES,upperDefault(input.priority(),"NORMAL"),"Prioridad inválida");require(TRIGGERS,upperDefault(input.triggerSource(),"MANUAL"),"Origen inválido");text(input.title(),220,true);
        if(input.scheduledStart()==null||input.scheduledEnd()==null||!input.scheduledEnd().isAfter(input.scheduledStart()))throw new IllegalArgumentException("Indique una ventana de mantenimiento válida");
    }
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?new LinkedHashMap<>():rows.get(0);}
    private Map<String,Object> required(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new NoSuchElementException("Registro no encontrado");return rows.get(0);}
    private static void require(Set<String> values,String v,String msg){if(!values.contains(v))throw new IllegalArgumentException(msg);}
    private static String upper(Object v){return v==null?"":String.valueOf(v).trim().toUpperCase(Locale.ROOT);}
    private static String upperDefault(Object v,String fallback){String x=upper(v);return x.isBlank()?fallback:x;}
    private static String clean(Object v){if(v==null)return null;String s=String.valueOf(v).trim();return s.isEmpty()?null:s;}
    private static String text(Object v,int max,boolean required){String s=clean(v);if(required&&s==null)throw new IllegalArgumentException("Complete los campos obligatorios");if(s!=null&&s.length()>max)throw new IllegalArgumentException("El texto supera "+max+" caracteres");return s;}
    private static UUID uuid(Object v){return v instanceof UUID u?u:UUID.fromString(String.valueOf(v));}
    private static BigDecimal bd(Object v){if(v==null)return null;return v instanceof BigDecimal b?b:new BigDecimal(String.valueOf(v));}
    private static Integer asInt(Object v){return v==null?null:(v instanceof Number n?n.intValue():Integer.valueOf(String.valueOf(v)));}
    private static OffsetDateTime asOffset(Object v){if(v==null)return null;if(v instanceof OffsetDateTime o)return o;if(v instanceof java.sql.Timestamp t)return t.toInstant().atOffset(ZoneOffset.UTC);return OffsetDateTime.parse(String.valueOf(v));}
    private static BigDecimal nonNegative(BigDecimal input,BigDecimal fallback,String msg){BigDecimal v=input==null?fallback:input;if(v==null)v=BigDecimal.ZERO;if(v.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException(msg);return v;}
    private static BigDecimal positiveOrNull(BigDecimal v,String msg){if(v==null)return null;if(v.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException(msg);return v;}
}
