package com.ngtintoreria.erp.dashboard;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    public DashboardController(DashboardService dashboard){this.dashboard=dashboard;}

    // Resumen disponible para usuarios autenticados; Spring Security protege /api/**.
    // DASHBOARD_READ controla el menú en el frontend.
    // Evita errores 403 por permisos desactualizados tras una actualización.
    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public Map<String,Object> summary(@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to){return dashboard.summary(from,to);}

    @GetMapping("/financial")
    @PreAuthorize("hasAuthority('COSTING_READ') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> financial(@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to){return dashboard.financial(from,to);}
}
