package com.ngtintoreria.erp.costing;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/costing")
public class IndustrialCostController {
    private final IndustrialCostService service;
    public IndustrialCostController(IndustrialCostService service){this.service=service;}

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('COSTING_READ')")
    public Map<String,Object> settings(){return service.settings();}

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('COSTING_CONFIG')")
    public Map<String,Object> saveSettings(@RequestBody IndustrialCostService.SettingsRequest input, Authentication auth){return service.saveSettings(input,auth.getName());}

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('COSTING_READ')")
    public Map<String,Object> summary(){return service.summary();}

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('COSTING_READ')")
    public List<Map<String,Object>> orders(){return service.orders();}

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('COSTING_READ')")
    public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}

    @PostMapping("/orders/{id}/calculate")
    @PreAuthorize("hasAuthority('COSTING_CALCULATE')")
    public Map<String,Object> calculate(@PathVariable UUID id,@RequestBody(required=false) IndustrialCostService.CalculateRequest input,Authentication auth){return service.calculate(id,input,auth.getName());}
}
