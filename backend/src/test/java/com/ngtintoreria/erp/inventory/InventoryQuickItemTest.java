package com.ngtintoreria.erp.inventory;

import com.ngtintoreria.erp.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InventoryQuickItemTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AuditService audit=mock(AuditService.class);
    private final InventoryService service=new InventoryService(jdbc,audit);

    private InventoryService.ItemRequest request(String code,String name){
        return new InventoryService.ItemRequest(null,code,name,"KG",
                BigDecimal.ZERO,BigDecimal.ZERO,null,true);
    }

    @Test void rejectIncompleteCatalogItems(){
        assertThrows(IllegalArgumentException.class,()->service.saveItem(request("SAL-001"," "),"qa"));
        assertThrows(IllegalArgumentException.class,()->service.saveItem(request("SAL 001","Sal industrial"),"qa"));
        assertThrows(IllegalArgumentException.class,()->service.saveItem(request("SAL-001","a".repeat(181)),"qa"));
        verifyNoInteractions(jdbc);
    }

    @Test void rejectExistingCodeIgnoringCase(){
        when(jdbc.queryForList(anyString(),any(Object.class),any(Object.class)))
            .thenReturn(List.of(Map.of("id",UUID.randomUUID(),"code","SAL-001","name","Sal industrial")));
        var ex=assertThrows(IllegalArgumentException.class,
                ()->service.saveItem(request("sal-001","Sal de otra marca"),"qa"));
        assertTrue(ex.getMessage().contains("código"));
        verify(jdbc,never()).queryForObject(startsWith("insert into inventory_items"),eq(UUID.class),any());
    }

    @Test void rejectExistingNameIgnoringCase(){
        when(jdbc.queryForList(anyString(),any(Object.class),any(Object.class)))
            .thenReturn(List.of(Map.of("id",UUID.randomUUID(),"code","SAL-001","name","Sal industrial")));
        var ex=assertThrows(IllegalArgumentException.class,
                ()->service.saveItem(request("NUEVO-001","sal INDUSTRIAL"),"qa"));
        assertTrue(ex.getMessage().contains("nombre"));
    }

    @Test void quickEndpointCannotEditAnExistingCatalogItem(){
        InventoryController controller=new InventoryController(service);
        var existing=new InventoryService.ItemRequest(UUID.randomUUID(),"SAL-001","Sal industrial",
                "KG",BigDecimal.ZERO,BigDecimal.ZERO,null,true);
        assertThrows(IllegalArgumentException.class,
                ()->controller.createQuickItem(existing,mock(Authentication.class)));
        verifyNoInteractions(jdbc);
    }
}
