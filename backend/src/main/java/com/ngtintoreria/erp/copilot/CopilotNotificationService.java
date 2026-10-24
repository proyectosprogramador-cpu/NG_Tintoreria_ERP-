package com.ngtintoreria.erp.copilot;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.*;
import java.util.*;

@Service
public class CopilotNotificationService {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final JdbcTemplate jdbc;
    private final CopilotProactiveService proactive;
    private final AuditService audit;
    private final ObjectProvider<JavaMailSender> mailProvider;
    private final boolean enabled;
    private final boolean dryRun;
    private final String emailFrom;
    private final String smtpHost;
    private final String whatsappWebhookUrl;
    private final String whatsappWebhookToken;

    public CopilotNotificationService(JdbcTemplate jdbc,
                                      CopilotProactiveService proactive,
                                      AuditService audit,
                                      ObjectProvider<JavaMailSender> mailProvider,
                                      @Value("${app.copilot.notifications.enabled:true}") boolean enabled,
                                      @Value("${app.copilot.notifications.dry-run:true}") boolean dryRun,
                                      @Value("${app.copilot.notifications.email-from:no-reply@ng.local}") String emailFrom,
                                      @Value("${spring.mail.host:}") String smtpHost,
                                      @Value("${app.copilot.notifications.whatsapp-webhook-url:}") String whatsappWebhookUrl,
                                      @Value("${app.copilot.notifications.whatsapp-webhook-token:}") String whatsappWebhookToken) {
        this.jdbc = jdbc;
        this.proactive = proactive;
        this.audit = audit;
        this.mailProvider = mailProvider;
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.emailFrom = emailFrom == null ? "" : emailFrom.trim();
        this.smtpHost = smtpHost == null ? "" : smtpHost.trim();
        this.whatsappWebhookUrl = whatsappWebhookUrl == null ? "" : whatsappWebhookUrl.trim();
        this.whatsappWebhookToken = whatsappWebhookToken == null ? "" : whatsappWebhookToken.trim();
    }

    public Map<String,Object> status() {
        long contacts = scalar("select count(*) from copilot_notification_contacts where active=true");
        long rules = scalar("select count(*) from copilot_notification_rules where enabled=true");
        long simulated = scalar("select count(*) from copilot_notification_outbox where status='SIMULATED' and created_at>=now()-interval '24 hours'");
        long sent = scalar("select count(*) from copilot_notification_outbox where status='SENT' and created_at>=now()-interval '24 hours'");
        long failed = scalar("select count(*) from copilot_notification_outbox where status='FAILED' and created_at>=now()-interval '24 hours'");
        LinkedHashMap<String,Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("dryRun", dryRun);
        out.put("mode", dryRun ? "SIMULATION" : "LIVE");
        out.put("emailConfigured", !smtpHost.isBlank());
        out.put("whatsappConfigured", !whatsappWebhookUrl.isBlank());
        out.put("contacts", contacts);
        out.put("activeRules", rules);
        out.put("sent24h", sent);
        out.put("simulated24h", simulated);
        out.put("failed24h", failed);
        out.put("emailFrom", emailFrom);
        return out;
    }

    public List<Map<String,Object>> contacts() {
        return jdbc.queryForList("""
            select id,name,role_code,email,whatsapp,email_enabled,whatsapp_enabled,active,created_at,updated_at
              from copilot_notification_contacts
             order by active desc,role_code,lower(name)
            """);
    }

    @Transactional
    public Map<String,Object> saveContact(UUID id, String name, String roleCode, String email, String whatsapp,
                                          boolean emailEnabled, boolean whatsappEnabled, boolean active) {
        String safeName = req(name,"Nombre");
        String safeRole = req(roleCode,"Rol").toUpperCase(Locale.ROOT);
        String safeEmail = blankToNull(email);
        String safeWhatsapp = blankToNull(whatsapp);
        if (emailEnabled && safeEmail == null) throw new IllegalArgumentException("Ingrese un correo antes de habilitar Email");
        if (whatsappEnabled && safeWhatsapp == null) throw new IllegalArgumentException("Ingrese un número antes de habilitar WhatsApp");
        UUID target = id == null ? UUID.randomUUID() : id;
        int updated = jdbc.update("""
            update copilot_notification_contacts
               set name=?,role_code=?,email=?,whatsapp=?,email_enabled=?,whatsapp_enabled=?,active=?,updated_at=now()
             where id=?
            """, safeName,safeRole,safeEmail,safeWhatsapp,emailEnabled,whatsappEnabled,active,target);
        if (updated == 0) {
            jdbc.update("""
                insert into copilot_notification_contacts(id,name,role_code,email,whatsapp,email_enabled,whatsapp_enabled,active,created_at,updated_at)
                values (?,?,?,?,?,?,?,?,now(),now())
                """,target,safeName,safeRole,safeEmail,safeWhatsapp,emailEnabled,whatsappEnabled,active);
        }
        audit.record("COPILOT_NOTIFICATION_CONTACT_SAVED","Comunicaciones IA",target.toString(),safeName+" · "+safeRole);
        return jdbc.queryForMap("select * from copilot_notification_contacts where id=?",target);
    }

    public List<Map<String,Object>> rules() {
        return jdbc.queryForList("""
            select id,category,min_severity,channels,role_codes,enabled,repeat_minutes,quiet_start,quiet_end,created_at,updated_at
              from copilot_notification_rules
             order by category
            """);
    }

    @Transactional
    public Map<String,Object> saveRule(UUID id, String category, String minSeverity, String channels, String roleCodes,
                                       boolean enabled, int repeatMinutes, String quietStart, String quietEnd) {
        String cat=req(category,"Categoría").toUpperCase(Locale.ROOT);
        String sev=req(minSeverity,"Severidad").toUpperCase(Locale.ROOT);
        if (!Set.of("INFO","IMPORTANT","CRITICAL").contains(sev)) throw new IllegalArgumentException("Severidad no válida");
        String ch=normalizeCsv(channels,Set.of("EMAIL","WHATSAPP"));
        if (ch.isBlank()) throw new IllegalArgumentException("Seleccione al menos un canal");
        String roles=normalizeCsv(roleCodes,null);
        if (roles.isBlank()) throw new IllegalArgumentException("Ingrese al menos un rol destinatario");
        int repeat=Math.max(30,Math.min(repeatMinutes,10080));
        LocalTime qs=parseTime(quietStart); LocalTime qe=parseTime(quietEnd);
        UUID target=id==null?UUID.randomUUID():id;
        int updated=jdbc.update("""
            update copilot_notification_rules set category=?,min_severity=?,channels=?,role_codes=?,enabled=?,repeat_minutes=?,quiet_start=?,quiet_end=?,updated_at=now() where id=?
            """,cat,sev,ch,roles,enabled,repeat,qs,qe,target);
        if(updated==0){
            try {
                jdbc.update("""
                    insert into copilot_notification_rules(id,category,min_severity,channels,role_codes,enabled,repeat_minutes,quiet_start,quiet_end,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,now(),now())
                    """,target,cat,sev,ch,roles,enabled,repeat,qs,qe);
            } catch (Exception ex) {
                jdbc.update("""
                    update copilot_notification_rules set min_severity=?,channels=?,role_codes=?,enabled=?,repeat_minutes=?,quiet_start=?,quiet_end=?,updated_at=now() where category=?
                    """,sev,ch,roles,enabled,repeat,qs,qe,cat);
                target=jdbc.queryForObject("select id from copilot_notification_rules where category=?",UUID.class,cat);
            }
        }
        audit.record("COPILOT_NOTIFICATION_RULE_SAVED","Comunicaciones IA",target.toString(),cat+" · "+sev+" · "+ch);
        return jdbc.queryForMap("select * from copilot_notification_rules where id=?",target);
    }

    public List<Map<String,Object>> outbox(int limit) {
        int safe=Math.min(Math.max(limit,1),200);
        return jdbc.queryForList("""
            select id,alert_key,category,severity,channel,recipient,subject,message,status,attempt_count,last_error,provider_response,created_at,sent_at
              from copilot_notification_outbox order by created_at desc limit ?
            """,safe);
    }

    @Transactional
    public Map<String,Object> sendTest(UUID contactId, String channel) {
        Map<String,Object> c=jdbc.queryForMap("select * from copilot_notification_contacts where id=?",contactId);
        String ch=req(channel,"Canal").toUpperCase(Locale.ROOT);
        String recipient=recipient(c,ch);
        if(recipient==null) throw new IllegalArgumentException("El contacto no tiene un destino configurado para "+ch);
        CopilotProactiveService.ProactiveAlert a=new CopilotProactiveService.ProactiveAlert(
                "test:"+UUID.randomUUID(),"INFO","TEST","Prueba de comunicaciones NG Copiloto",
                "Este es un mensaje de prueba de la integración de comunicaciones.",
                "No representa una incidencia real del ERP.","dashboard","TEST",null,null,Map.of(),OffsetDateTime.now());
        UUID id=insertOutbox(a,ch,recipient,"TEST-"+UUID.randomUUID());
        deliver(id);
        audit.record("COPILOT_NOTIFICATION_TEST","Comunicaciones IA",id.toString(),ch+" · "+recipient);
        return jdbc.queryForMap("select * from copilot_notification_outbox where id=?",id);
    }

    @Scheduled(fixedDelayString="${app.copilot.notifications.scan-ms:60000}")
    public void scanAndDispatch() {
        if(!enabled) return;
        try {
            List<CopilotProactiveService.ProactiveAlert> alerts=proactive.systemAlerts();
            if(alerts.isEmpty()) return;
            List<Map<String,Object>> rules=jdbc.queryForList("select * from copilot_notification_rules where enabled=true");
            List<Map<String,Object>> contacts=jdbc.queryForList("select * from copilot_notification_contacts where active=true");
            long epoch=Instant.now().getEpochSecond();
            for(CopilotProactiveService.ProactiveAlert a:alerts){
                for(Map<String,Object> r:rules){
                    if(!a.category().equalsIgnoreCase(String.valueOf(r.get("category")))) continue;
                    if(!severityAtLeast(a.severity(),String.valueOf(r.get("min_severity")))) continue;
                    if(inQuietHours(r.get("quiet_start"),r.get("quiet_end"))) continue;
                    Set<String> roles=csvSet(r.get("role_codes"));
                    Set<String> channels=csvSet(r.get("channels"));
                    int repeat=((Number)r.getOrDefault("repeat_minutes",240)).intValue();
                    long bucket=epoch/Math.max(1800L,repeat*60L);
                    for(Map<String,Object> c:contacts){
                        String role=String.valueOf(c.get("role_code")).toUpperCase(Locale.ROOT);
                        if(!roles.contains("ALL")&&!roles.contains(role)) continue;
                        if(channels.contains("EMAIL")&&Boolean.TRUE.equals(c.get("email_enabled"))){
                            enqueue(a,"EMAIL",blankToNull(String.valueOf(c.get("email"))),bucket);
                        }
                        if(channels.contains("WHATSAPP")&&Boolean.TRUE.equals(c.get("whatsapp_enabled"))){
                            enqueue(a,"WHATSAPP",blankToNull(String.valueOf(c.get("whatsapp"))),bucket);
                        }
                    }
                }
            }
        } catch (Exception e) {
            audit.recordAs("SYSTEM","COPILOT_NOTIFICATION_SCAN_FAILED","Comunicaciones IA",null,trim(e.getMessage(),600),false);
        }
    }

    private void enqueue(CopilotProactiveService.ProactiveAlert a,String channel,String recipient,long bucket){
        if(recipient==null)return;
        String dedupe=a.key()+"|"+channel+"|"+recipient.toLowerCase(Locale.ROOT)+"|"+bucket;
        int rows=jdbc.update("""
            insert into copilot_notification_outbox(id,alert_key,category,severity,channel,recipient,subject,message,status,attempt_count,dedupe_key,created_at)
            values (gen_random_uuid(),?,?,?,?,?,?,?,'PENDING',0,?,now()) on conflict(dedupe_key) do nothing
            """,a.key(),a.category(),a.severity(),channel,recipient,subject(a),body(a),dedupe);
        if(rows==1){UUID id=jdbc.queryForObject("select id from copilot_notification_outbox where dedupe_key=?",UUID.class,dedupe);if(id!=null)deliver(id);}
    }

    private UUID insertOutbox(CopilotProactiveService.ProactiveAlert a,String channel,String recipient,String dedupe){
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into copilot_notification_outbox(id,alert_key,category,severity,channel,recipient,subject,message,status,attempt_count,dedupe_key,created_at)
            values (?,?,?,?,?,?,?,?, 'PENDING',0,?,now())
            """,id,a.key(),a.category(),a.severity(),channel,recipient,subject(a),body(a),dedupe);
        return id;
    }

    private void deliver(UUID id){
        Map<String,Object> row=jdbc.queryForMap("select * from copilot_notification_outbox where id=?",id);
        String channel=String.valueOf(row.get("channel"));
        String recipient=String.valueOf(row.get("recipient"));
        String subject=String.valueOf(row.get("subject"));
        String message=String.valueOf(row.get("message"));
        jdbc.update("update copilot_notification_outbox set attempt_count=attempt_count+1 where id=?",id);
        if(dryRun){
            jdbc.update("update copilot_notification_outbox set status='SIMULATED',provider_response='DRY_RUN: no se realizó envío externo',sent_at=now() where id=?",id);
            audit.recordAs("SYSTEM","COPILOT_NOTIFICATION_SIMULATED","Comunicaciones IA",id.toString(),channel+" · "+recipient,true);
            return;
        }
        try{
            String response;
            if("EMAIL".equals(channel)) response=sendEmail(recipient,subject,message);
            else if("WHATSAPP".equals(channel)) response=sendWhatsapp(recipient,message,row);
            else throw new IllegalArgumentException("Canal no soportado: "+channel);
            jdbc.update("update copilot_notification_outbox set status='SENT',provider_response=?,last_error=null,sent_at=now() where id=?",trim(response,2000),id);
            audit.recordAs("SYSTEM","COPILOT_NOTIFICATION_SENT","Comunicaciones IA",id.toString(),channel+" · "+recipient,true);
        }catch(Exception e){
            jdbc.update("update copilot_notification_outbox set status='FAILED',last_error=? where id=?",trim(e.getMessage(),2000),id);
            audit.recordAs("SYSTEM","COPILOT_NOTIFICATION_FAILED","Comunicaciones IA",id.toString(),channel+" · "+recipient+" · "+trim(e.getMessage(),500),false);
        }
    }

    private String sendEmail(String to,String subject,String message){
        if(smtpHost.isBlank()) throw new IllegalStateException("SMTP no configurado");
        JavaMailSender sender=mailProvider.getIfAvailable();
        if(sender==null) throw new IllegalStateException("Servicio de correo no disponible");
        SimpleMailMessage m=new SimpleMailMessage();
        if(!emailFrom.isBlank())m.setFrom(emailFrom);
        m.setTo(to);m.setSubject(subject);m.setText(message);sender.send(m);
        return "SMTP OK";
    }

    private String sendWhatsapp(String to,String message,Map<String,Object> row){
        if(whatsappWebhookUrl.isBlank()) throw new IllegalStateException("Webhook de WhatsApp no configurado");
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("to",to);payload.put("message",message);payload.put("category",row.get("category"));payload.put("severity",row.get("severity"));payload.put("alertKey",row.get("alert_key"));
        RestClient.RequestBodySpec req=RestClient.create().post().uri(whatsappWebhookUrl).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if(!whatsappWebhookToken.isBlank())req.header("Authorization","Bearer "+whatsappWebhookToken);
        req.body(payload).retrieve().toBodilessEntity();
        return "WEBHOOK OK";
    }

    private String subject(CopilotProactiveService.ProactiveAlert a){
        return "[NG ERP]["+labelSeverity(a.severity())+"] "+a.title()+(a.reference()==null||a.reference().isBlank()?"":" - "+a.reference());
    }
    private String body(CopilotProactiveService.ProactiveAlert a){
        return "NG Copiloto Industrial\n\n"+labelSeverity(a.severity())+" · "+a.title()+"\n"+a.message()+"\n"+a.detail()+
                (a.reference()==null||a.reference().isBlank()?"":"\nReferencia: "+a.reference())+"\n\nGenerado: "+ZonedDateTime.now(LIMA)+"\nERP Tintorería";
    }
    private String labelSeverity(String s){return switch(String.valueOf(s).toUpperCase(Locale.ROOT)){case "CRITICAL"->"CRÍTICA";case "IMPORTANT"->"IMPORTANTE";default->"INFO";};}
    private boolean severityAtLeast(String alert,String min){return severityValue(alert)>=severityValue(min);}
    private int severityValue(String s){return switch(String.valueOf(s).toUpperCase(Locale.ROOT)){case "CRITICAL"->3;case "IMPORTANT"->2;default->1;};}
    private Set<String> csvSet(Object raw){Set<String> out=new LinkedHashSet<>();if(raw==null)return out;for(String x:String.valueOf(raw).split(",")){String v=x.trim().toUpperCase(Locale.ROOT);if(!v.isBlank())out.add(v);}return out;}
    private String normalizeCsv(String raw,Set<String> allowed){LinkedHashSet<String> out=new LinkedHashSet<>();if(raw!=null)for(String x:raw.split(",")){String v=x.trim().toUpperCase(Locale.ROOT);if(v.isBlank())continue;if(allowed==null||allowed.contains(v))out.add(v);}return String.join(",",out);}
    private boolean inQuietHours(Object start,Object end){LocalTime s=asLocalTime(start),e=asLocalTime(end);if(s==null||e==null)return false;LocalTime now=LocalTime.now(LIMA);if(s.equals(e))return false;return s.isBefore(e)?(!now.isBefore(s)&&now.isBefore(e)):(!now.isBefore(s)||now.isBefore(e));}
    private LocalTime asLocalTime(Object x){if(x==null)return null;if(x instanceof LocalTime t)return t;try{return LocalTime.parse(String.valueOf(x));}catch(Exception e){return null;}}
    private LocalTime parseTime(String x){if(x==null||x.isBlank())return null;try{return LocalTime.parse(x);}catch(Exception e){throw new IllegalArgumentException("Hora no válida: "+x);}}
    private String recipient(Map<String,Object> c,String channel){Object v="EMAIL".equals(channel)?c.get("email"):"WHATSAPP".equals(channel)?c.get("whatsapp"):null;return v==null?null:blankToNull(String.valueOf(v));}
    private long scalar(String sql){Long x=jdbc.queryForObject(sql,Long.class);return x==null?0:x;}
    private String req(String s,String field){if(s==null||s.isBlank())throw new IllegalArgumentException(field+" es obligatorio");return s.trim();}
    private String blankToNull(String s){if(s==null)return null;String v=s.trim();return v.isBlank()||"null".equalsIgnoreCase(v)?null:v;}
    private String trim(String s,int max){if(s==null)return null;return s.length()<=max?s:s.substring(0,max);}
}
