package com.ngtintoreria.erp.security;

import org.springframework.security.core.Authentication;

import java.util.Set;

/**
 * Restricción complementaria de reportes por rol. La autorización específica
 * de cada informe se sigue validando en ReportController con @PreAuthorize.
 *
 * El perfil exclusivamente Producción recibe solo informes de su propio proceso.
 * Si se asigna intencionalmente otro rol, se respetan sus permisos acumulados.
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
     * Defensa adicional: el rol exclusivamente Producción no accede a APIs
     * financieras aunque una asignación posterior le devuelva COSTING_READ.
     * Los usuarios con otros roles conservan los permisos específicos de cada endpoint.
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
