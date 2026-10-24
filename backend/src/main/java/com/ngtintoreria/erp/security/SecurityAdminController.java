package com.ngtintoreria.erp.security;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/security-admin")
public class SecurityAdminController {
    private final JdbcTemplate jdbc;
    private final AuthSessionRepository sessions;
    private final AuditService audit;

    public SecurityAdminController(JdbcTemplate jdbc, AuthSessionRepository sessions, AuditService audit) {
        this.jdbc=jdbc; this.sessions=sessions; this.audit=audit;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('SECURITY_READ') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> summary() {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("database", Objects.equals(jdbc.queryForObject("select 1",Integer.class),1)?"UP":"DOWN");
        out.put("activeUsers", scalar("select count(*) from app_users where active=true"));
        out.put("lockedUsers", scalar("select count(*) from app_users where locked_until>now()"));
        out.put("failedLogins24h", scalar("select count(*) from audit_events where action='LOGIN_FAILED' and created_at>=now()-interval '24 hours'"));
        out.put("activeSessions", sessions.countActive(OffsetDateTime.now()));
        out.put("auditEvents30d", scalar("select count(*) from audit_events where created_at>=now()-interval '30 days'"));
        Long dbBytes=jdbc.queryForObject("select pg_database_size(current_database())",Long.class);
        out.put("databaseBytes", dbBytes==null?0:dbBytes);
        List<Map<String,Object>> last=jdbc.queryForList("select filename,size_bytes,sha256,status,created_at,verified_at,restored_at from system_backup_history order by created_at desc limit 1");
        out.put("lastBackup", last.isEmpty()?null:last.get(0));
        out.put("serverTime",OffsetDateTime.now());
        return out;
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAuthority('SECURITY_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> activeSessions() {
        return sessions.findAllActive(OffsetDateTime.now()).stream().map(s -> {
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",s.getId()); m.put("user",s.getUser().getEmail()); m.put("displayName",s.getUser().getDisplayName());
            m.put("issuedAt",s.getIssuedAt()); m.put("expiresAt",s.getExpiresAt()); m.put("lastSeenAt",s.getLastSeenAt());
            m.put("ipAddress",s.getIpAddress()); m.put("userAgent",s.getUserAgent());
            return m;
        }).toList();
    }

    @DeleteMapping("/sessions/{id}")
    @PreAuthorize("hasAuthority('SECURITY_MANAGE') or hasAuthority('ROLE_ADMIN')")
    @Transactional
    public Map<String,Object> revoke(@PathVariable UUID id) {
        AuthSession s=sessions.findById(id).orElseThrow();
        s.setRevokedAt(OffsetDateTime.now()); sessions.save(s);
        audit.record("SESSION_REVOKED_ADMIN", "Seguridad", id.toString(), "Sesión de "+s.getUser().getEmail()+" revocada por administrador");
        return Map.of("ok",true);
    }

    @GetMapping("/backups")
    @PreAuthorize("hasAuthority('BACKUP_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> backups(@RequestParam(defaultValue="30") int limit) {
        int safe=Math.min(Math.max(limit,1),200);
        return jdbc.queryForList("select id,filename,size_bytes,sha256,status,created_by,created_at,verified_at,restored_at,notes from system_backup_history order by created_at desc limit ?",safe);
    }

    private long scalar(String sql) {
        Long x=jdbc.queryForObject(sql,Long.class); return x==null?0:x;
    }
}
