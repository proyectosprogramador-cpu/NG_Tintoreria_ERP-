package com.ngtintoreria.erp.procurement;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {
    private final PurchaseOrderService service;
    public PurchaseOrderController(PurchaseOrderService service){this.service=service;}

    @GetMapping
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_READ')")
    public List<Map<String,Object>> list(){return service.list();}

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_READ')")
    public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}

    @PostMapping("/from-request/{requestId}")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_WRITE')")
    public Map<String,Object> fromRequest(@PathVariable UUID requestId, Authentication auth){return service.fromRequest(requestId,auth.getName());}

    @PostMapping("/manual")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_WRITE')")
    public Map<String,Object> manual(@RequestBody PurchaseOrderService.ManualCreate input,Authentication auth){return service.createManual(input,auth.getName());}

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_WRITE')")
    public Map<String,Object> update(@PathVariable UUID id,@RequestBody PurchaseOrderService.DraftUpdate input,Authentication auth){return service.updateDraft(id,input,auth.getName());}

    @PostMapping("/{id}/action")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_WRITE')")
    public Map<String,Object> action(@PathVariable UUID id,@RequestBody PurchaseOrderService.ActionRequest input,Authentication auth){
        boolean canApprove=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("PURCHASE_ORDERS_APPROVE")||a.getAuthority().equals("ROLE_ADMIN"));
        return service.action(id,input,auth.getName(),canApprove);
    }

    @PostMapping("/{id}/receipts")
    @PreAuthorize("hasAuthority('PURCHASE_RECEIPTS')")
    public Map<String,Object> receive(@PathVariable UUID id,@RequestBody PurchaseOrderService.ReceiptRequest input,Authentication auth){return service.receive(id,input,auth.getName());}
}
