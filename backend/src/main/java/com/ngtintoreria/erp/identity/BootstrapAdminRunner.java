package com.ngtintoreria.erp.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminRunner implements ApplicationRunner {
    private final AppUserRepository users; private final RoleRepository roles; private final PasswordEncoder encoder;
    private final String email, password, name;
    public BootstrapAdminRunner(AppUserRepository users, RoleRepository roles, PasswordEncoder encoder,
                                @Value("${app.bootstrap.admin-email}") String email,
                                @Value("${app.bootstrap.admin-password}") String password,
                                @Value("${app.bootstrap.admin-name}") String name) {
        this.users=users; this.roles=roles; this.encoder=encoder; this.email=email; this.password=password; this.name=name;
    }
    @Override public void run(ApplicationArguments args) {
        if (users.count()==0) {
            if (password.length()<10) throw new IllegalStateException("ERP_BOOTSTRAP_ADMIN_PASSWORD debe tener al menos 10 caracteres");
            AppUser admin = new AppUser(email,name,encoder.encode(password));
            admin.getRoles().add(roles.findByCode("ADMIN").orElseThrow());
            users.save(admin);
            System.out.println("[NG ERP] Administrador inicial creado: " + email + " (cambie la contraseña al iniciar el piloto)");
        }
    }
}
