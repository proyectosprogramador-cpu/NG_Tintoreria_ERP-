package com.ngtintoreria.erp.systemhealth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/system-health")
public class SystemHealthController {
    private static final String APP_VERSION = "0.13.2-R2";
    // Las migraciones del JAR determinan la versión requerida del esquema.
    private static final Pattern VERSIONED_SQL_MIGRATION =
            Pattern.compile("^V([0-9][0-9._]*)__.+\\.sql$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final Path storageRoot;
    private final Environment springEnvironment;
    private final boolean defaultJwtSecret;
    private final boolean defaultDbPassword;

    public SystemHealthController(JdbcTemplate jdbc,
                                  ObjectMapper objectMapper,
                                  AuditService audit,
                                  Environment springEnvironment,
                                  @Value("${app.storage.root:/app/data/files}") String storageRoot,
                                  @Value("${app.jwt.secret}") String jwtSecret,
                                  @Value("${spring.datasource.password}") String dbPassword) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.springEnvironment = springEnvironment;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.defaultJwtSecret = "NGTintoreriaERP-Local-Secret-Change-2026-Strong-Key".equals(jwtSecret);
        this.defaultDbPassword = "ngerp_local_2026".equals(dbPassword);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('SYSTEM_HEALTH_READ') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> summary() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("version", APP_VERSION);
        out.put("serverTime", OffsetDateTime.now());
        out.put("environment", environment());
        out.put("health", calculateHealth());
        return out;
    }

    @GetMapping("/failures")
    @PreAuthorize("hasAuthority('SYSTEM_HEALTH_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> failures(@RequestParam(defaultValue = "30") int limit) {
        int safe = Math.min(Math.max(limit,1),100);
        try {
            return jdbc.queryForList("""
                    select created_at,actor,module,action,ip_address,http_method,request_path,detail
                    from audit_events
                    where success=false
                    order by created_at desc
                    limit ?
                    """, safe);
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('SYSTEM_HEALTH_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> runs(@RequestParam(defaultValue = "30") int limit) {
        int safe = Math.min(Math.max(limit,1),100);
        return jdbc.queryForList("""
                select id,overall_status,blocker_count,warning_count,run_by,created_at
                from system_preflight_runs
                order by created_at desc
                limit ?
                """, safe);
    }

    @PostMapping("/preflight")
    @PreAuthorize("hasAuthority('SYSTEM_HEALTH_MANAGE') or hasAuthority('ROLE_ADMIN')")
    @Transactional
    public Map<String,Object> preflight(Authentication auth) {
        Map<String,Object> health = calculateHealth();
        String overall = String.valueOf(health.getOrDefault("overallStatus","BLOCKED"));
        int blockers = ((Number)health.getOrDefault("blockerCount",0)).intValue();
        int warnings = ((Number)health.getOrDefault("warningCount",0)).intValue();
        String json;
        try {
            json = objectMapper.writeValueAsString(health.getOrDefault("checks",List.of()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo registrar el resultado del preflight");
        }
        String actor = auth == null ? "SYSTEM" : auth.getName();
        Map<String,Object> inserted = jdbc.queryForMap("""
                insert into system_preflight_runs(overall_status,blocker_count,warning_count,checks_json,run_by)
                values (?,?,?,?,?)
                returning id,overall_status,blocker_count,warning_count,run_by,created_at
                """, overall, blockers, warnings, json, actor);
        audit.record("SYSTEM_PREFLIGHT_RUN","Estado del sistema",String.valueOf(inserted.get("id")),
                "Preflight: "+overall+", bloqueos="+blockers+", advertencias="+warnings);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("run", inserted);
        out.put("health", health);
        return out;
    }

    @PutMapping("/environment")
    @PreAuthorize("hasAuthority('SYSTEM_HEALTH_MANAGE') or hasAuthority('ROLE_ADMIN')")
    @Transactional
    public Map<String,Object> updateEnvironment(@RequestBody EnvironmentRequest req, Authentication auth) {
        String stage = req.deploymentStage()==null ? "PREPRODUCTION" : req.deploymentStage().trim().toUpperCase(Locale.ROOT);
        if(!stage.equals("PREPRODUCTION") && !stage.equals("PRODUCTION"))
            throw new IllegalArgumentException("Etapa no valida");
        if(stage.equals("PRODUCTION")) {
            Map<String,Object> health = calculateHealth();
            if(!Objects.equals(health.get("overallStatus"),"READY"))
                throw new IllegalArgumentException("No puede pasar a PRODUCCION mientras el preflight tenga bloqueos o advertencias");
        }
        String actor = auth == null ? "SYSTEM" : auth.getName();
        jdbc.update("""
                insert into system_environment_config(id,deployment_stage,planned_go_live,responsible,notes,updated_by,updated_at)
                values (1,?,?,?,?,?,now())
                on conflict(id) do update set deployment_stage=excluded.deployment_stage,
                  planned_go_live=excluded.planned_go_live,responsible=excluded.responsible,
                  notes=excluded.notes,updated_by=excluded.updated_by,updated_at=now()
                """, stage, req.plannedGoLive(), blankToNull(req.responsible()), blankToNull(req.notes()), actor);
        audit.record("DEPLOYMENT_STAGE_UPDATED","Estado del sistema","1","Etapa operativa: "+stage);
        return environment();
    }

    private Map<String,Object> calculateHealth() {
        Map<String,Object> out = new LinkedHashMap<>();
        List<Map<String,Object>> checks = new ArrayList<>();

        boolean dbUp = false;
        long dbBytes = 0;
        String flyway = "?";
        try {
            Integer one = jdbc.queryForObject("select 1", Integer.class);
            dbUp = one != null && one == 1;
            Long size = jdbc.queryForObject("select pg_database_size(current_database())", Long.class);
            dbBytes = size == null ? 0 : size;
            String v = jdbc.queryForObject("select version from flyway_schema_history where success=true order by installed_rank desc limit 1", String.class);
            if(v!=null) flyway=v;
        } catch (DataAccessException ignored) {}
        out.put("backend","UP");
        out.put("database",dbUp?"UP":"DOWN");
        out.put("databaseBytes",dbBytes);
        out.put("flywayVersion",flyway);
        List<String> activeProfiles=Arrays.asList(springEnvironment.getActiveProfiles());
        out.put("activeProfiles",activeProfiles);
        addCheck(checks,"DATABASE","Base de datos PostgreSQL",dbUp?"PASS":"BLOCK",dbUp?"Conectividad correcta":"No responde PostgreSQL");
        FlywayCheck migrationCheck=verifyFlywayMigrations(flyway,dbUp);
        addCheck(checks,"FLYWAY","Migraciones Flyway",migrationCheck.status(),migrationCheck.detail());
        boolean demoProfile=activeProfiles.stream().anyMatch(p->"demo".equalsIgnoreCase(p));
        addCheck(checks,"SPRING_PROFILE","Perfil de ejecución",demoProfile?"BLOCK":"PASS",demoProfile?"El perfil demo está activo; antes del go-live cambie SPRING_PROFILES_ACTIVE=prod en .env y reinicie":"Perfil sin carga automática de datos demo");
        addCheck(checks,"JWT_SECRET","Secreto JWT",defaultJwtSecret?"BLOCK":"PASS",defaultJwtSecret?"El secreto JWT sigue usando el valor inicial; cambie ERP_JWT_SECRET en .env":"Secreto JWT personalizado");
        addCheck(checks,"DB_PASSWORD","Contraseña PostgreSQL",defaultDbPassword?"BLOCK":"PASS",defaultDbPassword?"La contraseña PostgreSQL sigue usando el valor inicial; cambie POSTGRES_PASSWORD antes del go-live":"Contraseña PostgreSQL personalizada");

        Map<String,Object> storage = storageInfo();
        out.put("storage",storage);
        boolean writable = Boolean.TRUE.equals(storage.get("writable"));
        long usable = ((Number)storage.getOrDefault("usableBytes",0L)).longValue();
        addCheck(checks,"STORAGE_WRITABLE","Almacenamiento documental",writable?"PASS":"BLOCK",writable?"Volumen accesible y escribible":"No se puede escribir en el volumen documental");
        if(usable < 1024L*1024L*1024L) addCheck(checks,"STORAGE_FREE","Espacio disponible","BLOCK","Menos de 1 GB disponible");
        else if(usable < 5L*1024L*1024L*1024L) addCheck(checks,"STORAGE_FREE","Espacio disponible","WARN","Menos de 5 GB disponible");
        else addCheck(checks,"STORAGE_FREE","Espacio disponible","PASS","Capacidad libre suficiente para operación");

        Map<String,Object> backup = lastBackup();
        out.put("lastBackup",backup.isEmpty()?null:backup);
        if(backup.isEmpty()) {
            addCheck(checks,"BACKUP","Backup integral verificado","BLOCK","No existe un backup registrado");
        } else {
            String status = String.valueOf(backup.getOrDefault("status",""));
            Number ageN=(Number)backup.getOrDefault("age_hours",999999d);
            double age=ageN.doubleValue();
            if(!"VERIFIED".equalsIgnoreCase(status) && !"RESTORED".equalsIgnoreCase(status))
                addCheck(checks,"BACKUP","Backup integral verificado","BLOCK","Último backup no está verificado");
            else if(age>72) addCheck(checks,"BACKUP","Backup integral reciente","BLOCK","Último backup tiene más de 72 horas");
            else if(age>24) addCheck(checks,"BACKUP","Backup integral reciente","WARN","Último backup tiene más de 24 horas");
            else addCheck(checks,"BACKUP","Backup integral reciente","PASS","Backup verificado dentro de las últimas 24 horas");
        }

        Map<String,Object> restore = restoreInfo();
        out.put("restore",restore);
        Number restoreAge=(Number)restore.get("age_days");
        if(restoreAge==null) addCheck(checks,"RESTORE_TEST","Prueba de restauración","WARN","Aún no existe una restauración registrada");
        else if(restoreAge.doubleValue()>90) addCheck(checks,"RESTORE_TEST","Prueba de restauración","WARN","La última restauración tiene más de 90 días");
        else addCheck(checks,"RESTORE_TEST","Prueba de restauración","PASS","Existe una restauración registrada recientemente");

        long failed24 = scalar("select count(*) from audit_events where success=false and created_at>=now()-interval '24 hours'");
        out.put("failedEvents24h",failed24);
        addCheck(checks,"RECENT_ERRORS","Errores auditados últimas 24 h",failed24==0?"PASS":"WARN",failed24==0?"Sin errores auditados":"Se registraron "+failed24+" eventos fallidos; revisar detalle");

        long activeUsers = scalar("select count(*) from app_users where active=true");
        long lockedUsers = scalar("select count(*) from app_users where locked_until>now()");
        long temporaryPasswords = scalar("select count(*) from app_users where active=true and must_change_password=true");
        long demoData =
    scalar("select (select count(*) from clients where active=true and (document_number='20123456789' or coalesce(email,'') like '%.demo')) + (select count(*) from suppliers where active=true and (ruc='20987654321' or coalesce(email,'') like '%.demo'))");
        out.put("activeUsers",activeUsers);
        out.put("lockedUsers",lockedUsers);
        out.put("temporaryPasswords",temporaryPasswords);
        out.put("demoDataCount",demoData);
        addCheck(checks,"USERS","Usuarios habilitados",activeUsers>0?"PASS":"BLOCK",activeUsers+" usuarios activos");
        addCheck(checks,"TEMP_PASSWORDS","Contraseñas temporales",temporaryPasswords==0?"PASS":"BLOCK",temporaryPasswords==0?"Todos los usuarios activos completaron su cambio de contraseña":temporaryPasswords+" usuarios activos aún tienen cambio de contraseña pendiente");
        addCheck(checks,"DEMO_DATA","Datos demostrativos",demoData==0?"PASS":"WARN",demoData==0?"No se detectaron registros demo activos":
demoData+" registros demo activos siguen presentes; revise antes del go-live");

        Map<String,Object> company = companyInfo();
        out.put("company",company);
        boolean companyReady = Boolean.TRUE.equals(company.get("complete"));
        boolean logo = Boolean.TRUE.equals(company.get("logoConfigured"));
        addCheck(checks,"COMPANY","Datos de empresa",companyReady?"PASS":"BLOCK",companyReady?"Razón social/comercial y RUC configurados":"Complete datos principales de Empresa");
        addCheck(checks,"LOGO","Logo corporativo",logo?"PASS":"WARN",logo?"Logo configurado":"No hay logo corporativo cargado");

        Map<String,Object> pilot = pilotInfo();
        out.put("pilot",pilot);
        boolean pilotReady = Boolean.TRUE.equals(pilot.get("ready"));
        addCheck(checks,"PILOT","Piloto productivo",pilotReady?"PASS":"BLOCK",String.valueOf(pilot.getOrDefault("detail","Sin información")));

        long highIncidents = ((Number)pilot.getOrDefault("blockingIncidents",0L)).longValue();
        addCheck(checks,"PILOT_INCIDENTS","Incidencias críticas/altas",highIncidents==0?"PASS":"BLOCK",highIncidents==0?"Sin incidencias bloqueantes":highIncidents+" incidencias críticas/altas pendientes");

        int blockers = (int)checks.stream().filter(c->"BLOCK".equals(c.get("status"))).count();
        int warnings = (int)checks.stream().filter(c->"WARN".equals(c.get("status"))).count();
        String overall = blockers>0?"BLOCKED":warnings>0?"ATTENTION":"READY";
        out.put("checks",checks);
        out.put("blockerCount",blockers);
        out.put("warningCount",warnings);
        out.put("overallStatus",overall);
        return out;
    }


    private record FlywayCheck(String status, String detail) {}

    /** Compara las migraciones SQL del backend desplegado con las aplicadas en PostgreSQL.
     * Bloquea si faltan migraciones, hubo fallos o el esquema está por delante del JAR.
     * No aplica migraciones ni modifica la base de datos.
     */
    private FlywayCheck verifyFlywayMigrations(String installedVersion, boolean dbUp) {
        if(!dbUp) return new FlywayCheck("BLOCK", "No se puede verificar Flyway sin PostgreSQL");
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver(getClass().getClassLoader())
                    .getResources("classpath*:db/migration/V*__*.sql");
            Set<String> bundled = new TreeSet<>();
            MigrationVersion requiredVersion = null;
            for(Resource resource : resources) {
                String script = resource.getFilename();
                if(script == null) continue;
                Matcher match = VERSIONED_SQL_MIGRATION.matcher(script);
                if(!match.matches()) continue;
                MigrationVersion version = MigrationVersion.fromVersion(match.group(1).replace('_','.'));
                if(requiredVersion == null || version.compareTo(requiredVersion) > 0) requiredVersion = version;
                bundled.add(script);
            }
            if(bundled.isEmpty() || requiredVersion == null)
                return new FlywayCheck("BLOCK", "No se encontraron migraciones SQL dentro del backend desplegado");

            List<Map<String,Object>> records = jdbc.queryForList(
                    "select script,success from flyway_schema_history where version is not null and type='SQL'");
            Set<String> applied = new TreeSet<>();
            int failed = 0;
            for(Map<String,Object> record : records) {
                if(Boolean.TRUE.equals(record.get("success"))) applied.add(String.valueOf(record.get("script")));
                else failed++;
            }
            Set<String> pending = new TreeSet<>(bundled);
            pending.removeAll(applied);
            Set<String> notInBackend = new TreeSet<>(applied);
            notInBackend.removeAll(bundled);

            String prefix = "Esquema actual: V"+installedVersion+
                    " · requerido por backend: V"+requiredVersion;
            if(failed > 0)
                return new FlywayCheck("BLOCK",prefix+" · "+failed+" migración(es) fallida(s)");
            if(!pending.isEmpty())
                return new FlywayCheck("BLOCK",prefix+" · pendientes: "+previewScripts(pending));
            if(!notInBackend.isEmpty())
                return new FlywayCheck("BLOCK",prefix+" · aplicadas sin script en este backend: "+previewScripts(notInBackend));
            return new FlywayCheck("PASS",prefix+" · "+bundled.size()+" migraciones SQL verificadas");
        } catch(Exception e) {
            return new FlywayCheck("BLOCK", "No se pudieron contrastar migraciones Flyway con el backend: "+e.getClass().getSimpleName());
        }
    }

    private String previewScripts(Set<String> scripts) {
        String preview=String.join(", ",scripts.stream().limit(3).toList());
        return scripts.size()>3 ? preview+" y "+(scripts.size()-3)+" más" : preview;
    }

    private Map<String,Object> storageInfo() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("root","/app/data/files");
        try {
            Files.createDirectories(storageRoot);
            FileStore store = Files.getFileStore(storageRoot);
            out.put("totalBytes",store.getTotalSpace());
            out.put("usableBytes",store.getUsableSpace());
            out.put("writable",Files.isWritable(storageRoot));
            long count=0,size=0;
            try(var stream=Files.walk(storageRoot)) {
                Iterator<Path> it=stream.filter(Files::isRegularFile).iterator();
                while(it.hasNext()) {
                    Path p=it.next(); count++;
                    try { size += Files.size(p); } catch(IOException ignored) {}
                }
            }
            out.put("fileCount",count);
            out.put("filesBytes",size);
        } catch(IOException ex) {
            out.put("totalBytes",0L); out.put("usableBytes",0L); out.put("writable",false);
            out.put("fileCount",0L); out.put("filesBytes",0L);
        }
        return out;
    }

    private Map<String,Object> lastBackup() {
        try {
            List<Map<String,Object>> rows=jdbc.queryForList("""
                    select filename,size_bytes,sha256,status,created_at,verified_at,restored_at,
                      extract(epoch from (now()-created_at))/3600.0 as age_hours
                    from system_backup_history
                    order by created_at desc limit 1
                    """);
            return rows.isEmpty()?Map.of():rows.get(0);
        } catch(DataAccessException ex) { return Map.of(); }
    }

    private Map<String,Object> restoreInfo() {
        try {
            List<Map<String,Object>> rows=jdbc.queryForList("""
                    select max(restored_at) as last_restored_at,
                      case when max(restored_at) is null then null
                           else extract(epoch from (now()-max(restored_at)))/86400.0 end as age_days
                    from system_backup_history
                    """);
            return rows.isEmpty()?Map.of():rows.get(0);
        } catch(DataAccessException ex) { return Map.of(); }
    }

    private Map<String,Object> companyInfo() {
        try {
            List<Map<String,Object>> rows=jdbc.queryForList("""
                    select trade_name,legal_name,ruc,logo_storage_name
                    from company_profile order by updated_at desc limit 1
                    """);
            if(rows.isEmpty()) return Map.of("complete",false,"logoConfigured",false);
            Map<String,Object> r=rows.get(0);
            boolean complete=nonBlank(r.get("trade_name")) && nonBlank(r.get("ruc"));
            boolean logo=nonBlank(r.get("logo_storage_name"));
            Map<String,Object> out=new LinkedHashMap<>(r);out.put("complete",complete);out.put("logoConfigured",logo);return out;
        } catch(DataAccessException ex) { return Map.of("complete",false,"logoConfigured",false); }
    }

    private Map<String,Object> pilotInfo() {
        Map<String,Object> out=new LinkedHashMap<>();
        try {
            List<Map<String,Object>> cycles=jdbc.queryForList("select id,code,status from pilot_cycles order by created_at desc limit 1");
            if(cycles.isEmpty()) { out.put("ready",false);out.put("detail","No existe ciclo de piloto");out.put("blockingIncidents",0L);return out; }
            Map<String,Object> c=cycles.get(0);
            UUID id=(UUID)c.get("id");
            Map<String,Object> counts=jdbc.queryForMap("""
                    select count(*) filter(where mandatory) as mandatory_total,
                           count(*) filter(where mandatory and status='PASSED') as mandatory_passed,
                           count(*) filter(where mandatory and status='BLOCKED') as mandatory_blocked
                    from pilot_checklist_items where cycle_id=?
                    """,id);
            Long incObj=jdbc.queryForObject("select count(*) from pilot_incidents where cycle_id=? and severity in ('CRITICAL','HIGH') and status not in ('VERIFIED','CLOSED')",Long.class,id);
            long inc=incObj==null?0:incObj;
            long total=((Number)counts.get("mandatory_total")).longValue();
            long passed=((Number)counts.get("mandatory_passed")).longValue();
            long blocked=((Number)counts.get("mandatory_blocked")).longValue();
            boolean ready=total>0 && total==passed && blocked==0 && inc==0;
            out.putAll(c);out.put("mandatoryTotal",total);out.put("mandatoryPassed",passed);out.put("mandatoryBlocked",blocked);out.put("blockingIncidents",inc);out.put("ready",ready);
            out.put("detail",passed+"/"+total+" puntos obligatorios validados · estado "+c.get("status"));
            return out;
        } catch(Exception ex) {
            out.put("ready",false);out.put("detail","No se pudo evaluar el piloto");out.put("blockingIncidents",0L);return out;
        }
    }

    private Map<String,Object> environment() {
        try {
            List<Map<String,Object>> rows=jdbc.queryForList("""
                    select deployment_stage,planned_go_live,responsible,notes,updated_by,updated_at
                    from system_environment_config where id=1
                    """);
            return rows.isEmpty()?Map.of("deployment_stage","PREPRODUCTION"):rows.get(0);
        } catch(DataAccessException ex) { return Map.of("deployment_stage","PREPRODUCTION"); }
    }

    private long scalar(String sql) {
        try { Long x=jdbc.queryForObject(sql,Long.class);return x==null?0:x; }
        catch(DataAccessException ex){return 0;}
    }

    private boolean nonBlank(Object x){return x!=null&&!String.valueOf(x).isBlank();}
    private String blankToNull(String s){return s==null||s.isBlank()?null:s.trim();}
    private void addCheck(List<Map<String,Object>> list,String code,String label,String status,String detail){
        list.add(Map.of("code",code,"label",label,"status",status,"detail",detail));
    }

    public record EnvironmentRequest(String deploymentStage, LocalDate plannedGoLive, String responsible, String notes) {}
}
