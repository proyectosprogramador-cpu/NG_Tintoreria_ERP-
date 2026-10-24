package com.ngtintoreria.erp.audit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/audit")
public class AuditController {
    private final AuditEventRepository repo;
    public AuditController(AuditEventRepository repo){this.repo=repo;}
    @GetMapping @PreAuthorize("hasAuthority('AUDIT_READ')")
    public List<AuditEvent> list(@RequestParam(defaultValue="100") int limit){return repo.findAll(PageRequest.of(0,Math.min(Math.max(limit,1),500),Sort.by(Sort.Direction.DESC,"createdAt"))).getContent();}
}
