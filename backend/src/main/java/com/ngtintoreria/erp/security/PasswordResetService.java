package com.ngtintoreria.erp.security;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.identity.AppUser;
import com.ngtintoreria.erp.identity.AppUserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

@Service
public class PasswordResetService {
    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final ObjectProvider<JavaMailSender> mailProvider;
    private final String smtpHost;
    private final String emailFrom;
    private final String publicBaseUrl;
    private final int expirationMinutes;
    private final SecureRandom random=new SecureRandom();

    public PasswordResetService(
            AppUserRepository users,
            PasswordResetTokenRepository tokens,
            AuthSessionRepository sessions,
            PasswordEncoder encoder,
            AuditService audit,
            ObjectProvider<JavaMailSender> mailProvider,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${app.security.password-reset-email-from:no-reply@ng.local}") String emailFrom,
            @Value("${app.security.password-reset-base-url:http://localhost:8080}") String publicBaseUrl,
            @Value("${app.security.password-reset-minutes:30}") int expirationMinutes) {
        this.users=users;
        this.tokens=tokens;
        this.sessions=sessions;
        this.encoder=encoder;
        this.audit=audit;
        this.mailProvider=mailProvider;
        this.smtpHost=smtpHost==null?"":smtpHost.trim();
        this.emailFrom=emailFrom==null?"":emailFrom.trim();
        this.publicBaseUrl=trimSlash(publicBaseUrl==null?"":publicBaseUrl.trim());
        this.expirationMinutes=Math.max(10,Math.min(expirationMinutes,120));
    }

    @Transactional
    public void request(String rawEmail,String ip){
        if(smtpHost.isBlank()) throw new IllegalStateException("La recuperación por correo todavía no está configurada en este entorno.");

        String email=rawEmail==null?"":rawEmail.trim().toLowerCase();
        AppUser user=users.findByEmailIgnoreCase(email).orElse(null);

        // Respuesta uniforme para no revelar si una cuenta existe.
        if(user==null || !user.isActive()){
            audit.recordAs(email,"PASSWORD_RESET_REQUEST","Seguridad",null,"Solicitud recibida para cuenta no disponible",true);
            return;
        }

        OffsetDateTime now=OffsetDateTime.now();
        var last=tokens.findTopByUserIdOrderByCreatedAtDesc(user.getId()).orElse(null);
        if(last!=null && last.getCreatedAt().isAfter(now.minusMinutes(2))){
            audit.recordAs(email,"PASSWORD_RESET_THROTTLED","Seguridad",user.getId().toString(),"Solicitud repetida dentro de 2 minutos",true);
            return;
        }

        byte[] bytes=new byte[32];
        random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash=sha256(token);
        tokens.save(new PasswordResetToken(user,hash,now,now.plusMinutes(expirationMinutes),ip));

        String link=publicBaseUrl+"/?resetToken="+token;
        sendMail(user,link);
        audit.recordAs(email,"PASSWORD_RESET_REQUEST","Seguridad",user.getId().toString(),"Enlace temporal enviado",true);
    }

    @Transactional
    public void reset(String token,String newPassword){
        if(token==null || token.isBlank()) throw new IllegalArgumentException("El enlace de recuperación no es válido.");
        PasswordPolicy.validate(newPassword);

        OffsetDateTime now=OffsetDateTime.now();
        PasswordResetToken reset=tokens.findByTokenHashAndUsedAtIsNull(sha256(token.trim()))
                .orElseThrow(() -> new IllegalArgumentException("El enlace de recuperación no es válido o ya fue utilizado."));

        if(reset.getExpiresAt().isBefore(now)){
            throw new IllegalArgumentException("El enlace de recuperación ha expirado. Solicite uno nuevo.");
        }

        AppUser user=reset.getUser();
        if(!user.isActive()) throw new IllegalArgumentException("La cuenta no está habilitada.");
        if(encoder.matches(newPassword,user.getPasswordHash()))
            throw new IllegalArgumentException("La nueva contraseña debe ser diferente a la anterior.");

        user.setPasswordHash(encoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setPasswordChangedAt(now);
        user.incrementSecurityVersion();
        users.save(user);

        reset.setUsedAt(now);
        tokens.save(reset);
        sessions.revokeAllForUser(user.getId(),now);
        audit.recordAs(user.getEmail(),"PASSWORD_RESET_COMPLETED","Seguridad",user.getId().toString(),"Contraseña recuperada; sesiones revocadas",true);
    }

    private void sendMail(AppUser user,String link){
        JavaMailSender sender=mailProvider.getIfAvailable();
        if(sender==null) throw new IllegalStateException("Servicio de correo no disponible.");

        SimpleMailMessage message=new SimpleMailMessage();
        if(!emailFrom.isBlank()) message.setFrom(emailFrom);
        message.setTo(user.getEmail());
        message.setSubject("Recuperación de acceso · NG Tintorería ERP");
        message.setText(
                "Hola "+user.getDisplayName()+",\n\n"+
                "Se solicitó restablecer la contraseña de tu cuenta en NG Tintorería ERP.\n\n"+
                "Abre este enlace para crear una nueva contraseña:\n"+link+"\n\n"+
                "El enlace vence en "+expirationMinutes+" minutos y solo puede usarse una vez.\n"+
                "Si no solicitaste este cambio, ignora este mensaje.\n\n"+
                "NG Tintorería ERP");
        sender.send(message);
    }

    private static String sha256(String value){
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        }catch(Exception ex){
            throw new IllegalStateException("No se pudo generar el token de seguridad",ex);
        }
    }

    private static String trimSlash(String value){
        while(value.endsWith("/")) value=value.substring(0,value.length()-1);
        return value;
    }
}
