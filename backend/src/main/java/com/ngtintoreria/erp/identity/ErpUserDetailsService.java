package com.ngtintoreria.erp.identity;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;

@Service
public class ErpUserDetailsService implements UserDetailsService {
    private final AppUserRepository repo;
    private final PermissionRepository permissions;

    public ErpUserDetailsService(AppUserRepository repo, PermissionRepository permissions) {
        this.repo = repo;
        this.permissions = permissions;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser u = repo.findByEmailIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));

        LinkedHashSet<SimpleGrantedAuthority> auths = new LinkedHashSet<>();
        boolean isAdmin = false;

        for (Role role : u.getRoles()) {
            auths.add(new SimpleGrantedAuthority("ROLE_" + role.getCode()));
            if ("ADMIN".equals(role.getCode())) isAdmin = true;
            role.getPermissions().forEach(p -> auths.add(new SimpleGrantedAuthority(p.getCode())));
        }

        // ADMIN conserva todos los permisos aunque falten enlaces en role_permissions.
        // Evita errores 403 tras agregar permisos en una migración.
        if (isAdmin) {
            permissions.findAll().forEach(p -> auths.add(new SimpleGrantedAuthority(p.getCode())));
        }

        return User.withUsername(u.getEmail())
                .password(u.getPasswordHash())
                .authorities(auths)
                .disabled(!u.isActive())
                .build();
    }
}
