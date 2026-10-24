package com.ngtintoreria.erp.receiving;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.crm.ClientRepository;
import com.ngtintoreria.erp.sales.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReceptionControllerTest {
    private FabricReceptionRepository receptions;
    private ReceptionRollRepository rolls;
    private ReceptionController controller;

    @BeforeEach void setup(){
        receptions=mock(FabricReceptionRepository.class);rolls=mock(ReceptionRollRepository.class);
        controller=new ReceptionController(receptions,rolls,mock(ReceptionEventRepository.class),mock(ReceptionAttachmentRepository.class),
                mock(ReceptionStorageService.class),mock(SalesOrderRepository.class),mock(SalesOrderItemRepository.class),
                mock(SalesOrderEventRepository.class),mock(ClientRepository.class),mock(AuditService.class));
    }

    @Test void createWithoutRollsIsRejectedBeforePersistence(){
        var request=new ReceptionController.ReceptionRequest(UUID.randomUUID(),LocalDate.now(),null,null,null,"Almacén",null,null,null,List.of());
        assertThatThrownBy(()->controller.create(request,mock(Authentication.class))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("al menos un rollo");
        verifyNoInteractions(receptions);
    }

    @Test void pendingRollCannotBeReleased(){
        UUID id=UUID.randomUUID();FabricReception reception=mock(FabricReception.class);ReceptionRoll pending=mock(ReceptionRoll.class);
        when(reception.getStatus()).thenReturn("IN_INSPECTION");when(reception.getId()).thenReturn(id);when(pending.getInspectionStatus()).thenReturn("PENDING");
        when(receptions.findById(id)).thenReturn(Optional.of(reception));when(rolls.findByReceptionIdOrderByLineNo(id)).thenReturn(List.of(pending));
        Authentication auth=mock(Authentication.class);when(auth.getAuthorities()).thenAnswer(x->List.of(new SimpleGrantedAuthority("RECEPTIONS_RELEASE")));
        var request=new ReceptionController.StatusRequest("RELEASED","Prueba");
        assertThatThrownBy(()->controller.status(id,request,auth)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Todos los rollos");
        verify(receptions,never()).save(any());
    }

    @Test void staleVersionCannotOverwriteReception(){
        UUID id=UUID.randomUUID(),orderId=UUID.randomUUID();FabricReception reception=mock(FabricReception.class);
        when(reception.getStatus()).thenReturn("DRAFT");when(reception.getSalesOrderId()).thenReturn(orderId);when(reception.getVersionNo()).thenReturn(3);when(receptions.findById(id)).thenReturn(Optional.of(reception));
        var roll=new ReceptionController.RollRequest(null,UUID.randomUUID(),null,"CLIENT",null,null,null,null,null,null,"PENDING",null,null);
        var request=new ReceptionController.ReceptionRequest(orderId,LocalDate.now(),null,null,null,"Almacén",null,null,2,List.of(roll));
        assertThatThrownBy(()->controller.update(id,request,mock(Authentication.class))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("otro usuario");
        verify(receptions,never()).save(any());
    }
}
