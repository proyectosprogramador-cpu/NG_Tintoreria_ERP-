package com.ngtintoreria.erp.security;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.identity.AppUser;
import com.ngtintoreria.erp.identity.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AppUserRepository users;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final int maxAttempts;
    private final int lockMinutes;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          AppUserRepository users,
                          AuthSessionRepository sessions,
                          PasswordEncoder encoder,
                          AuditService audit,
                          @Value("${app.security.max-login-attempts:5}") int maxAttempts,
                          @Value("${app.security.lock-minutes:15}") int lockMinutes) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.users = users;
        this.sessions = sessions;
        this.encoder = encoder;
        this.audit = audit;
        this.maxAttempts = Math.max(3, maxAttempts);
        this.lockMinutes = Math.max(5, lockMinutes);
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    public record UserView(String email, String displayName, boolean mustChangePassword, List<String> roles,
                           List<String> authorities, OffsetDateTime lastLoginAt, OffsetDateTime lockedUntil) {}
    public record LoginResponse(String token, UserView user) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record SessionView(UUID id, OffsetDateTime issuedAt, OffsetDateTime expiresAt, OffsetDateTime lastSeenAt,
                              String ipAddress, String userAgent, boolean current) {}

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String email = request.email().trim().toLowerCase();
        OffsetDateTime now = OffsetDateTime.now();
        AppUser u = users.findByEmailIgnoreCase(email).orElse(null);

        if (u == null) {
            audit.recordAs(email, "LOGIN_FAILED", "Seguridad", null, "Credenciales inválidas", false);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        if (!u.isActive()) {
            audit.recordAs(email, "LOGIN_BLOCKED_INACTIVE", "Seguridad", u.getId().toString(), "Usuario inactivo", false);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario inactivo");
        }
        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(now)) {
            audit.recordAs(email, "LOGIN_BLOCKED_LOCK", "Seguridad", u.getId().toString(), "Bloqueo temporal vigente", false);
            throw new ResponseStatusException(HttpStatus.LOCKED, "Cuenta bloqueada temporalmente hasta " + u.getLockedUntil());
        }
        if (u.getLockedUntil() != null && !u.getLockedUntil().isAfter(now)) {
            u.setLockedUntil(null);
            u.setFailedLoginAttempts(0);
            users.save(u);
        }

        Authentication auth;
        try {
            auth = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (org.springframework.security.core.AuthenticationException ex) {
            int attempts = u.getFailedLoginAttempts() + 1;
            u.setFailedLoginAttempts(attempts);
            if (attempts >= maxAttempts) {
                u.setLockedUntil(now.plusMinutes(lockMinutes));
                audit.recordAs(email, "ACCOUNT_LOCKED", "Seguridad", u.getId().toString(),
                        "Bloqueada por " + attempts + " intentos fallidos", false);
            } else {
                audit.recordAs(email, "LOGIN_FAILED", "Seguridad", u.getId().toString(),
                        "Intento fallido " + attempts + " de " + maxAttempts, false);
            }
            users.save(u);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }

        u.setFailedLoginAttempts(0);
        u.setLockedUntil(null);
        u.setLastLoginAt(now);
        users.save(u);

        UserDetails details = (UserDetails) auth.getPrincipal();
        UUID jti = UUID.randomUUID();
        OffsetDateTime expiresAt = now.plusMinutes(jwtService.getExpirationMinutes());
        sessions.save(new AuthSession(u, jti, now, expiresAt, clientIp(http), truncate(http.getHeader("User-Agent"), 500)));
        String token = jwtService.generate(details, jti, u.getSecurityVersion());
        audit.recordAs(email, "LOGIN_SUCCESS", "Seguridad", u.getId().toString(), "Inicio de sesión correcto", true);
        return new LoginResponse(token, toView(u, details.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList()));
    }

    @GetMapping("/me")
    public UserView me(Authentication auth) {
        AppUser u = users.findByEmailIgnoreCase(auth.getName()).orElseThrow();
        return toView(u, auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    }

    @PostMapping("/change-password")
    @Transactional
    public Map<String,Object> changePassword(Authentication auth, @Valid @RequestBody ChangePasswordRequest request) {
        AppUser u = users.findByEmailIgnoreCase(auth.getName()).orElseThrow();
        if (!encoder.matches(request.currentPassword(), u.getPasswordHash()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña actual no es correcta");
        PasswordPolicy.validate(request.newPassword());
        if (encoder.matches(request.newPassword(), u.getPasswordHash()))
            throw new IllegalArgumentException("La nueva contraseña debe ser diferente a la actual");

        u.setPasswordHash(encoder.encode(request.newPassword()));
        u.setMustChangePassword(false);
        u.setPasswordChangedAt(OffsetDateTime.now());
        u.incrementSecurityVersion();
        users.save(u);
        sessions.revokeAllForUser(u.getId(), OffsetDateTime.now());
        audit.record("PASSWORD_CHANGED", "Seguridad", u.getId().toString(), "Contraseña actualizada; sesiones revocadas");
        return Map.of("message", "Contraseña actualizada. Inicie sesión nuevamente.", "reauthenticate", true);
    }

    @PostMapping("/logout")
    @Transactional
    public Map<String,Object> logout(HttpServletRequest request, Authentication auth) {
        UUID jti = tokenJti(request);
        if (jti != null) sessions.findByJti(jti).ifPresent(s -> { s.setRevokedAt(OffsetDateTime.now()); sessions.save(s); });
        audit.record("LOGOUT", "Seguridad", null, "Cierre de sesión");
        return Map.of("ok", true);
    }

    @GetMapping("/sessions")
    public List<SessionView> mySessions(Authentication auth, HttpServletRequest request) {
        AppUser u = users.findByEmailIgnoreCase(auth.getName()).orElseThrow();
        UUID current = tokenJti(request);
        return sessions.findActiveByUser(u.getId(), OffsetDateTime.now()).stream().map(s ->
                new SessionView(s.getId(), s.getIssuedAt(), s.getExpiresAt(), s.getLastSeenAt(), s.getIpAddress(),
                        s.getUserAgent(), current != null && current.equals(s.getJti()))).toList();
    }

    @DeleteMapping("/sessions/{id}")
    @Transactional
    public Map<String,Object> revokeMySession(@PathVariable UUID id, Authentication auth) {
        AppUser u = users.findByEmailIgnoreCase(auth.getName()).orElseThrow();
        AuthSession s = sessions.findById(id).orElseThrow();
        if (!s.getUser().getId().equals(u.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sesión no pertenece al usuario");
        s.setRevokedAt(OffsetDateTime.now());
        sessions.save(s);
        audit.record("SESSION_REVOKED", "Seguridad", id.toString(), "Sesión revocada por su propietario");
        return Map.of("ok", true);
    }

    private UserView toView(AppUser u, List<String> authorities) {
        List<String> roles = u.getRoles().stream().map(r -> r.getCode()).sorted().toList();
        return new UserView(u.getEmail(), u.getDisplayName(), u.isMustChangePassword(), roles,
                authorities.stream().sorted().toList(), u.getLastLoginAt(), u.getLockedUntil());
    }

    private UUID tokenJti(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        try { return jwtService.extractJti(header.substring(7)); } catch (Exception ex) { return null; }
    }

    private String clientIp(HttpServletRequest req) {
        String forwarded=req.getHeader("X-Forwarded-For");
        if(forwarded!=null&&!forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return req.getRemoteAddr();
    }
    private String truncate(String s, int max) { return s==null?null:(s.length()<=max?s:s.substring(0,max)); }
}
