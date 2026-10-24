package com.ngtintoreria.erp.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService inventory;
    public InventoryController(InventoryService inventory){this.inventory=inventory;}

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('INVENTORY_READ')")
    public Map<String,Object> summary(){return inventory.summary();}

    @GetMapping("/items")
    @PreAuthorize("hasAuthority('INVENTORY_READ')")
    public List<Map<String,Object>> items(){return inventory.items();}

    @PostMapping("/items")
    @PreAuthorize("hasAuthority('INVENTORY_MOVEMENTS')")
    public Map<String,Object> saveItem(@RequestBody InventoryService.ItemRequest input, Authentication auth){return inventory.saveItem(input,auth.getName());}

    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('INVENTORY_READ')")
    public List<Map<String,Object>> movements(@RequestParam(defaultValue="200") int limit){return inventory.movements(limit);}

    @PostMapping("/movements")
    @PreAuthorize("hasAuthority('INVENTORY_MOVEMENTS')")
    public Map<String,Object> movement(@RequestBody InventoryService.MovementRequest input,Authentication auth){return inventory.recordMovement(input,auth.getName());}

    @GetMapping("/reservations")
    @PreAuthorize("hasAuthority('INVENTORY_READ')")
    public List<Map<String,Object>> reservations(){return inventory.reservations();}

    @PostMapping("/production-orders/{orderId}/reserve")
    @PreAuthorize("hasAuthority('INVENTORY_RESERVE')")
    public Map<String,Object> reserve(@PathVariable UUID orderId,Authentication auth){return inventory.reserveProductionOrder(orderId,auth.getName());}

    @GetMapping("/production-orders/{orderId}")
    @PreAuthorize("hasAuthority('INVENTORY_READ')")
    public Map<String,Object> productionSnapshot(@PathVariable UUID orderId){return inventory.productionMaterialSnapshot(orderId);}
}
