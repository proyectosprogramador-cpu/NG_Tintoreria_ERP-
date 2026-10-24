package com.ngtintoreria.erp.copilot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/copilot/notifications")
public class CopilotNotificationController {
    private final CopilotNotificationService service;
    public CopilotNotificationController(CopilotNotificationService service){this.service=service;}

    public record ContactRequest(UUID id,@NotBlank String name,@NotBlank String roleCode,String email,String whatsapp,boolean emailEnabled,boolean whatsappEnabled,boolean active){}
    public record RuleRequest(UUID id,@NotBlank String category,@NotBlank String minSeverity,@NotBlank String channels,@NotBlank String roleCodes,boolean enabled,int repeatMinutes,String quietStart,String quietEnd){}
    public record TestRequest(@NotNull UUID contactId,@NotBlank String channel){}

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('NOTIFICATIONS_READ') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> status(){return service.status();}

    @GetMapping("/contacts")
    @PreAuthorize("hasAuthority('NOTIFICATIONS_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> contacts(){return service.contacts();}

    @PostMapping("/contacts")
    @PreAuthorize("hasAuthority('NOTIFICATIONS_MANAGE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> saveContact(@Valid @RequestBody ContactRequest r){return service.saveContact(r.id(),r.name(),r.roleCode(),r.email(),r.whatsapp(),r.emailEnabled(),r.whatsappEnabled(),r.active());}

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('NOTIFICATIONS_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> rules(){return service.rules();}

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('NOTIFICATIONS_MANAGE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> saveRule(@Valid @RequestBody RuleRequest r){return service.saveRule(r.id(),r.category(),r.minSeverity(),r.channels(),r.roleCodes(),r.enabled(),r.repeatMinutes(),r.quietStart(),r.quietEnd());}

    @GetMapping("/outbox")
    @PreAuthorize("hasAuthority('NOTIFICATIONS_READ') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> outbox(@RequestParam(defaultValue="100") int limit){return service.outbox(limit);}

    @PostMapping("/test")
    @PreAuthorize("hasAuthority('NOTIFICATIONS_MANAGE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> test(@Valid @RequestBody TestRequest r){return service.sendTest(r.contactId(),r.channel());}
}
