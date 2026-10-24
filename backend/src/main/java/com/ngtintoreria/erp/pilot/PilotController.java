package com.ngtintoreria.erp.pilot;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/pilot")
public class PilotController {
    private static final Set<String> CYCLE_STATUSES=Set.of("PLANNING","IN_PROGRESS","READY","GO_LIVE","CLOSED");
    private static final Set<String> CHECK_STATUSES=Set.of("PENDING","IN_PROGRESS","PASSED","BLOCKED","NOT_APPLICABLE");
    private static final Set<String> INCIDENT_STATUSES=Set.of("OPEN","IN_PROGRESS","RESOLVED","VERIFIED","CLOSED");
    private static final Set<String> SEVERITIES=Set.of("CRITICAL","HIGH","MEDIUM","LOW");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PilotController(JdbcTemplate jdbc, AuditService audit){this.jdbc=jdbc;this.audit=audit;}

    @GetMapping("/cycle")
    @PreAuthorize("hasAuthority('PILOT_READ') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> cycle(){return activeCycle();}

    @PatchMapping("/cycle")
    @Transactional
    @PreAuthorize("hasAuthority('PILOT_WRITE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> updateCycle(@RequestBody Map<String,Object> body, Authentication auth){
        Map<String,Object> current=activeCycle(); UUID id=uuid(current.get("id"));
        String name=text(body.getOrDefault("name",current.get("name")),180,true);
        String status=upper(body.getOrDefault("status",current.get("status"))); require(CYCLE_STATUSES,status,"Estado de piloto inválido");
        if(Set.of("READY","GO_LIVE","CLOSED").contains(status)&&!canValidate(auth))throw new IllegalArgumentException("Se requiere permiso PILOT_VALIDATE para declarar listo/cerrar el piloto");
        if(Set.of("READY","GO_LIVE").contains(status)&&!readyForGoLive(id))throw new IllegalArgumentException("El piloto aún tiene puntos obligatorios o incidencias críticas/altas pendientes");
        LocalDate planned=body.containsKey("plannedGoLive")?parseNullableDate(body.get("plannedGoLive")):asDate(current.get("planned_go_live"));
        String notes=text(body.containsKey("notes")?body.get("notes"):current.get("notes"),4000,false);
        OffsetDateTime started=current.get("started_at") instanceof OffsetDateTime x?x:null;
        OffsetDateTime closed=current.get("closed_at") instanceof OffsetDateTime x?x:null;
        if("IN_PROGRESS".equals(status)&&started==null)started=OffsetDateTime.now();
        if("CLOSED".equals(status))closed=OffsetDateTime.now(); else if(!"CLOSED".equals(status))closed=null;
        jdbc.update("update pilot_cycles set name=?,status=?,planned_go_live=?,started_at=?,closed_at=?,notes=?,updated_at=now() where id=?",
                name,status,planned,started,closed,notes,id);
        audit.record("PILOT_CYCLE_UPDATED","Piloto",id.toString(),status+" · "+name);
        return activeCycle();
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('PILOT_READ') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> summary(){
        Map<String,Object> c=activeCycle(); UUID id=uuid(c.get("id"));
        Map<String,Object> checks=one("""
            select count(*) total,
                   count(*) filter(where mandatory) mandatory_total,
                   count(*) filter(where mandatory and status in ('PASSED','NOT_APPLICABLE')) mandatory_done,
                   count(*) filter(where status='PASSED') passed,
                   count(*) filter(where status='BLOCKED') blocked,
                   count(*) filter(where status in ('PENDING','IN_PROGRESS')) pending
              from pilot_checklist_items where cycle_id=?
            """,id);
        Map<String,Object> incidents=one("""
            select count(*) total,
                   count(*) filter(where status not in ('VERIFIED','CLOSED')) open_total,
                   count(*) filter(where severity='CRITICAL' and status not in ('VERIFIED','CLOSED')) critical_open,
                   count(*) filter(where severity='HIGH' and status not in ('VERIFIED','CLOSED')) high_open,
                   count(*) filter(where status='RESOLVED') awaiting_verification
              from pilot_incidents where cycle_id=?
            """,id);
        long mt=longVal(checks.get("mandatory_total")), md=longVal(checks.get("mandatory_done"));
        long blockerInc=longVal(incidents.get("critical_open"))+longVal(incidents.get("high_open"));
        long blocked=longVal(checks.get("blocked"));
        int readiness=mt==0?0:(int)Math.round((md*100.0)/mt);
        boolean ready=readiness==100&&blockerInc==0&&blocked==0;
        Map<String,Object> out=new LinkedHashMap<>();out.put("cycle",c);out.put("checklist",checks);out.put("incidents",incidents);out.put("readinessPercent",readiness);out.put("readyForGoLive",ready);out.put("blockers",blockerInc+blocked);return out;
    }

    @GetMapping("/checklist")
    @PreAuthorize("hasAuthority('PILOT_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> checklist(){
        UUID id=uuid(activeCycle().get("id"));
        return jdbc.queryForList("""
            select id,sort_order,area,title,description,mandatory,status,responsible,evidence,validated_by,validated_at,updated_by,updated_at
              from pilot_checklist_items where cycle_id=? order by sort_order,area,title
            """,id);
    }

    @PatchMapping("/checklist/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('PILOT_WRITE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> updateChecklist(@PathVariable UUID id,@RequestBody Map<String,Object> body,Authentication auth){
        Map<String,Object> cur=oneRequired("select * from pilot_checklist_items where id=?",id);
        String status=upper(body.getOrDefault("status",cur.get("status")));require(CHECK_STATUSES,status,"Estado de checklist inválido");
        if(Set.of("PASSED","NOT_APPLICABLE").contains(status)&&!canValidate(auth))throw new IllegalArgumentException("Se requiere permiso PILOT_VALIDATE para validar este punto");
        String responsible=text(body.containsKey("responsible")?body.get("responsible"):cur.get("responsible"),160,false);
        String evidence=text(body.containsKey("evidence")?body.get("evidence"):cur.get("evidence"),5000,false);
        String validatedBy=null;OffsetDateTime validatedAt=null;
        if(Set.of("PASSED","NOT_APPLICABLE").contains(status)){validatedBy=auth.getName();validatedAt=OffsetDateTime.now();}
        jdbc.update("update pilot_checklist_items set status=?,responsible=?,evidence=?,validated_by=?,validated_at=?,updated_by=?,updated_at=now() where id=?",
                status,responsible,evidence,validatedBy,validatedAt,auth.getName(),id);
        audit.record("PILOT_CHECK_UPDATED","Piloto",id.toString(),status+" · "+cur.get("title"));
        return oneRequired("select id,sort_order,area,title,description,mandatory,status,responsible,evidence,validated_by,validated_at,updated_by,updated_at from pilot_checklist_items where id=?",id);
    }

    @GetMapping("/incidents")
    @PreAuthorize("hasAuthority('PILOT_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> incidents(@RequestParam(required=false) String status,@RequestParam(required=false) String severity){
        UUID cycle=uuid(activeCycle().get("id")); StringBuilder sql=new StringBuilder("select * from pilot_incidents where cycle_id=?");List<Object> args=new ArrayList<>();args.add(cycle);
        if(status!=null&&!status.isBlank()){String s=upper(status);require(INCIDENT_STATUSES,s,"Estado de incidencia inválido");sql.append(" and status=?");args.add(s);}
        if(severity!=null&&!severity.isBlank()){String s=upper(severity);require(SEVERITIES,s,"Severidad inválida");sql.append(" and severity=?");args.add(s);}
        sql.append(" order by case severity when 'CRITICAL' then 1 when 'HIGH' then 2 when 'MEDIUM' then 3 else 4 end, reported_at desc");
        return jdbc.queryForList(sql.toString(),args.toArray());
    }

    @PostMapping("/incidents")
    @Transactional
    @PreAuthorize("hasAuthority('PILOT_WRITE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> createIncident(@RequestBody Map<String,Object> body,Authentication auth){
        UUID cycle=uuid(activeCycle().get("id"));Long seq=jdbc.queryForObject("select nextval('pilot_incident_seq')",Long.class);String number=String.format("INC-%06d",seq==null?1:seq);
        UUID id=UUID.randomUUID();String severity=upper(body.getOrDefault("severity","MEDIUM"));require(SEVERITIES,severity,"Severidad inválida");
        String module=text(body.get("module"),100,true),title=text(body.get("title"),220,true),description=text(body.get("description"),6000,true);
        String steps=text(body.get("stepsToReproduce"),6000,false),expected=text(body.get("expectedResult"),4000,false),actual=text(body.get("actualResult"),4000,false),assigned=text(body.get("assignedTo"),160,false);
        jdbc.update("""
            insert into pilot_incidents(id,cycle_id,incident_number,severity,status,module,title,description,steps_to_reproduce,expected_result,actual_result,assigned_to,reported_by)
            values(?,?,?,?, 'OPEN',?,?,?,?,?,?,?,?)
            """,id,cycle,number,severity,module,title,description,steps,expected,actual,assigned,auth.getName());
        audit.record("PILOT_INCIDENT_CREATED","Piloto",number,severity+" · "+module+" · "+title);
        return oneRequired("select * from pilot_incidents where id=?",id);
    }

    @PatchMapping("/incidents/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('PILOT_WRITE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> updateIncident(@PathVariable UUID id,@RequestBody Map<String,Object> body,Authentication auth){
        Map<String,Object> cur=oneRequired("select * from pilot_incidents where id=?",id);
        String severity=upper(body.getOrDefault("severity",cur.get("severity")));require(SEVERITIES,severity,"Severidad inválida");
        String status=upper(body.getOrDefault("status",cur.get("status")));require(INCIDENT_STATUSES,status,"Estado de incidencia inválido");
        if(Set.of("VERIFIED","CLOSED").contains(status)&&!canValidate(auth))throw new IllegalArgumentException("Se requiere permiso PILOT_VALIDATE para verificar/cerrar la incidencia");
        String module=text(body.containsKey("module")?body.get("module"):cur.get("module"),100,true);
        String title=text(body.containsKey("title")?body.get("title"):cur.get("title"),220,true);
        String description=text(body.containsKey("description")?body.get("description"):cur.get("description"),6000,true);
        String steps=text(body.containsKey("stepsToReproduce")?body.get("stepsToReproduce"):cur.get("steps_to_reproduce"),6000,false);
        String expected=text(body.containsKey("expectedResult")?body.get("expectedResult"):cur.get("expected_result"),4000,false);
        String actual=text(body.containsKey("actualResult")?body.get("actualResult"):cur.get("actual_result"),4000,false);
        String assigned=text(body.containsKey("assignedTo")?body.get("assignedTo"):cur.get("assigned_to"),160,false);
        String resolution=text(body.containsKey("resolution")?body.get("resolution"):cur.get("resolution"),6000,false);
        String resolvedBy=cur.get("resolved_by")==null?null:String.valueOf(cur.get("resolved_by"));OffsetDateTime resolvedAt=cur.get("resolved_at") instanceof OffsetDateTime x?x:null;
        String verifiedBy=cur.get("verified_by")==null?null:String.valueOf(cur.get("verified_by"));OffsetDateTime verifiedAt=cur.get("verified_at") instanceof OffsetDateTime x?x:null;
        if("RESOLVED".equals(status)&&resolvedAt==null){if(resolution==null||resolution.isBlank())throw new IllegalArgumentException("Registre la resolución antes de marcar RESOLVED");resolvedBy=auth.getName();resolvedAt=OffsetDateTime.now();}
        if(Set.of("VERIFIED","CLOSED").contains(status)){if(resolution==null||resolution.isBlank())throw new IllegalArgumentException("Registre la resolución antes de verificar/cerrar");if(resolvedAt==null){resolvedBy=auth.getName();resolvedAt=OffsetDateTime.now();}verifiedBy=auth.getName();verifiedAt=OffsetDateTime.now();}
        if(Set.of("OPEN","IN_PROGRESS").contains(status)){resolvedBy=null;resolvedAt=null;verifiedBy=null;verifiedAt=null;}
        jdbc.update("""
            update pilot_incidents set severity=?,status=?,module=?,title=?,description=?,steps_to_reproduce=?,expected_result=?,actual_result=?,assigned_to=?,resolution=?,resolved_by=?,resolved_at=?,verified_by=?,verified_at=?,updated_at=now() where id=?
            """,severity,status,module,title,description,steps,expected,actual,assigned,resolution,resolvedBy,resolvedAt,verifiedBy,verifiedAt,id);
        audit.record("PILOT_INCIDENT_UPDATED","Piloto",String.valueOf(cur.get("incident_number")),status+" · "+severity+" · "+title);
        return oneRequired("select * from pilot_incidents where id=?",id);
    }

    private Map<String,Object> activeCycle(){
        List<Map<String,Object>> rows=jdbc.queryForList("select * from pilot_cycles order by case when status='CLOSED' then 1 else 0 end, created_at desc limit 1");
        if(rows.isEmpty())throw new IllegalStateException("No existe ciclo de piloto configurado");return rows.get(0);
    }
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> r=jdbc.queryForList(sql,args);return r.isEmpty()?new LinkedHashMap<>():r.get(0);}
    private Map<String,Object> oneRequired(String sql,Object...args){List<Map<String,Object>> r=jdbc.queryForList(sql,args);if(r.isEmpty())throw new NoSuchElementException("Registro no encontrado");return r.get(0);}
    private boolean canValidate(Authentication auth){return auth.getAuthorities().stream().anyMatch(a->Set.of("PILOT_VALIDATE","ROLE_ADMIN").contains(a.getAuthority()));}
    private static void require(Set<String> allowed,String value,String msg){if(!allowed.contains(value))throw new IllegalArgumentException(msg);}
    private static String upper(Object o){return o==null?"":String.valueOf(o).trim().toUpperCase(Locale.ROOT);}
    private static UUID uuid(Object o){return o instanceof UUID u?u:UUID.fromString(String.valueOf(o));}
    private static long longVal(Object o){return o instanceof Number n?n.longValue():o==null?0:Long.parseLong(String.valueOf(o));}
    private static String text(Object o,int max,boolean required){String s=o==null?null:String.valueOf(o).trim();if(s!=null&&s.isEmpty())s=null;if(required&&s==null)throw new IllegalArgumentException("Complete los campos obligatorios");if(s!=null&&s.length()>max)throw new IllegalArgumentException("El texto supera "+max+" caracteres");return s;}
    private boolean readyForGoLive(UUID cycle){
        Map<String,Object> r=one("""
            select count(*) filter(where mandatory and status not in ('PASSED','NOT_APPLICABLE')) pending_mandatory,
                   count(*) filter(where status='BLOCKED') blocked
              from pilot_checklist_items where cycle_id=?
            """,cycle);
        Map<String,Object> i=one("""
            select count(*) filter(where severity in ('CRITICAL','HIGH') and status not in ('VERIFIED','CLOSED')) blockers
              from pilot_incidents where cycle_id=?
            """,cycle);
        return longVal(r.get("pending_mandatory"))==0&&longVal(r.get("blocked"))==0&&longVal(i.get("blockers"))==0;
    }
    private static LocalDate parseNullableDate(Object o){if(o==null||String.valueOf(o).isBlank())return null;return LocalDate.parse(String.valueOf(o));}
    private static LocalDate asDate(Object o){if(o==null)return null;if(o instanceof java.sql.Date d)return d.toLocalDate();if(o instanceof LocalDate d)return d;return LocalDate.parse(String.valueOf(o));}
}
