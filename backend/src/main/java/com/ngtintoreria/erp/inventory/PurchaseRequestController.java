package com.ngtintoreria.erp.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/purchase-requests")
public class PurchaseRequestController {
    private final PurchaseRequestService service;
    public PurchaseRequestController(PurchaseRequestService service){this.service=service;}

    @GetMapping
    @PreAuthorize("hasAuthority('PURCHASE_REQUESTS_READ')")
    public List<Map<String,Object>> list(){return service.list();}

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_REQUESTS_READ')")
    public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}

    @PostMapping("/manual")
    @PreAuthorize("hasAuthority('PURCHASE_REQUESTS_WRITE')")
    public Map<String,Object> manual(@RequestBody PurchaseRequestService.ManualCreate input,Authentication auth){
        return service.createManual(input,auth.getName());
    }

    @PostMapping("/from-production/{orderId}")
    @PreAuthorize("hasAuthority('PURCHASE_REQUESTS_WRITE')")
    public Map<String,Object> fromProduction(@PathVariable UUID orderId,Authentication auth){return service.fromProduction(orderId,auth.getName());}

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_REQUESTS_WRITE')")
    public Map<String,Object> update(@PathVariable UUID id,@RequestBody PurchaseRequestService.DraftUpdate input,Authentication auth){return service.updateDraft(id,input,auth.getName());}

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PURCHASE_REQUESTS_WRITE')")
    public Map<String,Object> status(@PathVariable UUID id,@RequestBody PurchaseRequestService.StatusRequest input,Authentication auth){
        boolean canApprove=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("PURCHASE_REQUESTS_APPROVE")||a.getAuthority().equals("ROLE_ADMIN"));
        return service.changeStatus(id,input,auth.getName(),canApprove);
    }
}
