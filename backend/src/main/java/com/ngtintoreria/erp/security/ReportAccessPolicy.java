package com.ngtintoreria.erp.security;

import org.springframework.security.core.Authentication;

import java.util.Set;

/**
 * Filtra reportes por rol; ReportController valida cada permiso con @PreAuthorize.
 * Un usuario con solo el rol Producción accede a reportes de su proceso.
 * Los usuarios con otros roles conservan sus permisos acumulados.
 */
public final class ReportAccessPolicy {
    private ReportAccessPolicy() {}

    private static final Set<String> PRODUCTION_PATHS = Set.of(
            "/api/reports/production/orders.xlsx",
            "/api/reports/production/orders.pdf",
            "/api/reports/production/consumption.xlsx",
            "/api/reports/production/consumption.pdf",
            "/api/reports/production/incidents.xlsx",
            "/api/reports/production/incidents.pdf"
    );

    /**
     * Bloquea las APIs financieras para usuarios con solo el rol Producción,
     * incluso si tienen COSTING_READ. Cada endpoint mantiene sus permisos específicos.
     */
    public static boolean canReadFinancial(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        boolean production = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PRODUCTION".equals(a.getAuthority()));
        boolean otherRole = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .anyMatch(code -> code != null && code.startsWith("ROLE_") && !"ROLE_PRODUCTION".equals(code));
        return !production || otherRole;
    }

    public static boolean canExport(Authentication authentication, String servletPath) {
        if (authentication == null || authentication.getAuthorities() == null || servletPath == null) {
            return false;
        }
        return canReadFinancial(authentication) || PRODUCTION_PATHS.contains(servletPath);
    }
}
