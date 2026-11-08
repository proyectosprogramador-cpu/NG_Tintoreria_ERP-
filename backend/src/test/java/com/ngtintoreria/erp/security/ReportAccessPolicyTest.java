package com.ngtintoreria.erp.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportAccessPolicyTest {
    private static Authentication user(String... authorities) {
        List<GrantedAuthority> permissions = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a))
                .toList();
        return new UsernamePasswordAuthenticationToken("prueba", "token", permissions);
    }

    @Test
    void produccionSoloPuedeConsultarTresReportesEnPdfOExcel() {
        Authentication production = user("ROLE_PRODUCTION", "REPORTS_EXPORT", "PRODUCTION_ORDERS_READ");
        for (String area : List.of("orders", "consumption", "incidents")) {
            for (String format : List.of("pdf", "xlsx")) {
                assertTrue(ReportAccessPolicy.canExport(
                        production, "/api/reports/production/" + area + "." + format));
            }
        }
    }

    @Test
    void produccionNoPuedeObtenerCostosNiReportesDeOtrasAreas() {
        Authentication production = user("ROLE_PRODUCTION", "REPORTS_EXPORT", "COSTING_READ", "ORDERS_READ");
        for (String path : List.of(
                "/api/reports/costing/orders.pdf",
                "/api/reports/costing/materials.xlsx",
                "/api/reports/dashboard/executive.pdf",
                "/api/reports/commercial/orders.pdf",
                "/api/reports/admin/clients.xlsx",
                "/api/reports/maintenance/work-orders.pdf",
                "/api/reports/production/orders.csv",
                "/api/reports/production/orders/123.pdf")) {
            assertFalse(ReportAccessPolicy.canExport(production, path), path);
        }
    }

    @Test
    void administradorYRolesCombinadosConservanPermisosDeOrigen() {
        assertTrue(ReportAccessPolicy.canExport(user("ROLE_ADMIN"), "/api/reports/costing/orders.pdf"));
        assertTrue(ReportAccessPolicy.canExport(
                user("ROLE_FINANCE", "REPORTS_EXPORT", "COSTING_READ"),
                "/api/reports/costing/orders.pdf"));
        assertTrue(ReportAccessPolicy.canExport(
                user("ROLE_PRODUCTION", "ROLE_FINANCE", "REPORTS_EXPORT"),
                "/api/reports/costing/orders.pdf"));
        // En estos casos ReportController todavía exige su @PreAuthorize por informe.
    }

    @Test
    void argumentosInvalidosSeRechazan() {
        assertFalse(ReportAccessPolicy.canExport(null, "/api/reports/production/orders.pdf"));
        assertFalse(ReportAccessPolicy.canExport(user("ROLE_PRODUCTION"), null));
    }
}
