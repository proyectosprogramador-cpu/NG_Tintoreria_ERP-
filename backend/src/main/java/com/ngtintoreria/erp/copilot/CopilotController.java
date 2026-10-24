package com.ngtintoreria.erp.copilot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/copilot")
public class CopilotController {
    private final CopilotService service;
    private final CopilotProactiveService proactive;
    public CopilotController(CopilotService service,CopilotProactiveService proactive){this.service=service;this.proactive=proactive;}

    public record AskRequest(@NotBlank String message,String context){}
    public record ReminderRequest(@NotBlank String message, OffsetDateTime remindAt){}
    public record AlertStateRequest(@NotBlank String key,@NotBlank String action,OffsetDateTime hiddenUntil){}
    public record ConfirmActionRequest(@NotBlank String type,Map<String,Object> payload){}

    @GetMapping("/capabilities")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> capabilities(Authentication auth){return service.capabilities(auth);}

    @PostMapping("/ask")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public CopilotService.AskResult ask(@Valid @RequestBody AskRequest req,Authentication auth){return service.ask(req.message(),req.context(),auth);}

    @GetMapping("/briefing")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public CopilotProactiveService.Briefing briefing(Authentication auth){return proactive.briefing(auth);}

    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public List<CopilotProactiveService.ProactiveAlert> alerts(@RequestParam(defaultValue="30") int limit,Authentication auth){return proactive.activeAlerts(auth,limit);}

    @GetMapping("/alerts/due")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public List<CopilotProactiveService.ProactiveAlert> dueAlerts(Authentication auth){return proactive.dueAlerts(auth);}

    @PostMapping("/alerts/state")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> alertState(@Valid @RequestBody AlertStateRequest req,Authentication auth){return proactive.updateState(auth.getName(),req.key(),req.action(),req.hiddenUntil());}

    @PostMapping("/actions/confirm")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> confirmAction(@Valid @RequestBody ConfirmActionRequest req,Authentication auth){return proactive.confirmAction(auth,req.type(),req.payload());}

    @GetMapping("/reminders")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> reminders(@RequestParam(defaultValue="20") int limit,Authentication auth){return service.reminders(auth.getName(),limit);}

    @GetMapping("/reminders/due")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public List<Map<String,Object>> due(Authentication auth){return service.dueReminders(auth.getName());}

    @PostMapping("/reminders")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> create(@Valid @RequestBody ReminderRequest req,Authentication auth){return service.createReminder(auth.getName(),req.message(),req.remindAt());}

    @PostMapping("/reminders/{id}/done")
    @PreAuthorize("hasAuthority('COPILOT_USE') or hasAuthority('ROLE_ADMIN')")
    public Map<String,Object> done(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean dismiss,Authentication auth){service.completeReminder(auth.getName(),id,dismiss);return Map.of("ok",true);}
}
