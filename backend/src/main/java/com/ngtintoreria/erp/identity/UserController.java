package com.ngtintoreria.erp.identity;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.security.AuthSessionRepository;
import com.ngtintoreria.erp.security.PasswordPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final AuthSessionRepository sessions;

    public UserController(AppUserRepository users, RoleRepository roles, PasswordEncoder encoder,
                          AuditService audit, AuthSessionRepository sessions) {
        this.users=users; this.roles=roles; this.encoder=encoder; this.audit=audit; this.sessions=sessions;
    }

    public record UserDto(UUID id, String email, String displayName, boolean active, boolean mustChangePassword,
                          List<String> roles, int failedLoginAttempts, OffsetDateTime lockedUntil,
                          OffsetDateTime lastLoginAt, OffsetDateTime passwordChangedAt) {}
    public record CreateUserRequest(@Email @NotBlank String email, @NotBlank String displayName, String password, Set<String> roles) {}
    public record UpdateUserRequest(String displayName, Boolean active, Set<String> roles, String newPassword) {}

    @GetMapping @PreAuthorize("hasAuthority('USERS_READ')")
    public List<UserDto> list() {
        return users.findAll().stream().sorted(Comparator.comparing(AppUser::getDisplayName)).map(this::dto).toList();
    }

    @PostMapping @PreAuthorize("hasAuthority('USERS_WRITE')")
    public UserDto create(@Valid @RequestBody CreateUserRequest req) {
        if (users.findByEmailIgnoreCase(req.email()).isPresent()) throw new IllegalArgumentException("El correo ya está registrado");
        PasswordPolicy.validate(req.password());
        AppUser u = new AppUser(req.email(), req.displayName(), encoder.encode(req.password()));
        u.setMustChangePassword(true);
        assignRoles(u, req.roles());
        u = users.save(u);
        audit.record("USUARIO_CREADO", "Usuarios", u.getId().toString(), u.getEmail()+" · contraseña temporal");
        return dto(u);
    }

    @PutMapping("/{id}") @PreAuthorize("hasAuthority('USERS_WRITE')")
    @Transactional
    public UserDto update(@PathVariable UUID id, @RequestBody UpdateUserRequest req, Authentication auth) {
        AppUser u = users.findById(id).orElseThrow();
        boolean self = u.getEmail().equalsIgnoreCase(auth.getName());
        if (self && Boolean.FALSE.equals(req.active())) throw new IllegalArgumentException("No puede desactivar su propio usuario");

        if (req.displayName()!=null && !req.displayName().isBlank()) u.setDisplayName(req.displayName());
        if (req.active()!=null) u.setActive(req.active());
        if (req.roles()!=null) {
            if (self && !req.roles().contains("ADMIN") && u.getRoles().stream().anyMatch(r->"ADMIN".equals(r.getCode())))
                throw new IllegalArgumentException("No puede quitarse a sí mismo el rol Administrador");
            assignRoles(u, req.roles());
        }
        boolean revoke = false;
        if (req.newPassword()!=null && !req.newPassword().isBlank()) {
            PasswordPolicy.validate(req.newPassword());
            u.setPasswordHash(encoder.encode(req.newPassword()));
            u.setMustChangePassword(true);
            u.setPasswordChangedAt(OffsetDateTime.now());
            u.incrementSecurityVersion();
            revoke = true;
        }
        if (Boolean.FALSE.equals(req.active())) { u.incrementSecurityVersion(); revoke = true; }
        u = users.save(u);
        if (revoke) sessions.revokeAllForUser(u.getId(), OffsetDateTime.now());
        audit.record("USUARIO_ACTUALIZADO", "Usuarios", u.getId().toString(), u.getEmail()+ (revoke?" · sesiones revocadas":""));
        return dto(u);
    }

    @PostMapping("/{id}/unlock") @PreAuthorize("hasAuthority('SECURITY_MANAGE')")
    public UserDto unlock(@PathVariable UUID id) {
        AppUser u=users.findById(id).orElseThrow();
        u.setFailedLoginAttempts(0); u.setLockedUntil(null); u=users.save(u);
        audit.record("ACCOUNT_UNLOCKED", "Seguridad", u.getId().toString(), u.getEmail());
        return dto(u);
    }

    @GetMapping("/roles") @PreAuthorize("hasAuthority('USERS_READ')")
    public List<Map<String,Object>> roleList() {
        return roles.findAll().stream().sorted(Comparator.comparing(Role::getName))
                .map(r -> Map.<String,Object>of("code",r.getCode(),"name",r.getName(),"permissions",r.getPermissions().stream().map(Permission::getCode).sorted().toList())).toList();
    }

    private void assignRoles(AppUser u, Set<String> codes) {
        u.getRoles().clear();
        Set<String> safe = (codes==null || codes.isEmpty()) ? Set.of("SALES") : codes;
        safe.forEach(code -> u.getRoles().add(roles.findByCode(code).orElseThrow(() -> new IllegalArgumentException("Rol inválido: "+code))));
    }
    private UserDto dto(AppUser u) {
        return new UserDto(u.getId(),u.getEmail(),u.getDisplayName(),u.isActive(),u.isMustChangePassword(),
                u.getRoles().stream().map(Role::getCode).sorted().toList(),u.getFailedLoginAttempts(),u.getLockedUntil(),
                u.getLastLoginAt(),u.getPasswordChangedAt());
    }
}
