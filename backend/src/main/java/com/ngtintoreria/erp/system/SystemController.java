package com.ngtintoreria.erp.system;

import com.ngtintoreria.erp.dashboard.DashboardService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private static final String VERSION = "0.13.2-R2";
    private final JdbcTemplate jdbc;
    private final DashboardService dashboard;

    public SystemController(JdbcTemplate jdbc, DashboardService dashboard) {
        this.jdbc = jdbc;
        this.dashboard = dashboard;
    }

    /** Endpoint mínimo y sin datos sensibles para que el actualizador pueda confirmar
     * que backend y frontend pertenecen a la misma revisión. */
    @GetMapping("/build")
    public Map<String,Object> build() {
        return Map.of("version", VERSION, "backend", "UP");
    }

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public Map<String,Object> status() {
        Integer one = jdbc.queryForObject("select 1", Integer.class);
        return Map.of(
                "version", VERSION,
                "backend", "UP",
                "database", one != null && one == 1 ? "UP" : "DOWN",
                "serverTime", OffsetDateTime.now().toString(),
                "mode", "LAN_PRODUCTION_CORE"
        );
    }

    /**
     * R4: el resumen se sirve bajo /system porque /system/status ya demostró funcionar
     * correctamente con el mismo JWT en la instalación. Solo exige sesión autenticada.
     */
    @GetMapping("/dashboard-summary")
    @PreAuthorize("isAuthenticated()")
    public Map<String,Object> dashboardSummary(@RequestParam(required=false) LocalDate from,
                                               @RequestParam(required=false) LocalDate to,
                                               Authentication auth) {
        Map<String,Object> out = new LinkedHashMap<>(dashboard.summary(from, to));
        out.put("servedBy", "system-r5");
        out.put("authenticatedAs", auth.getName());
        return out;
    }

    @GetMapping("/dashboard-financial")
    @PreAuthorize("hasAuthority('COSTING_READ') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> dashboardFinancial(@RequestParam(required=false) LocalDate from,
                                                 @RequestParam(required=false) LocalDate to) {
        return dashboard.financial(from, to);
    }
}
