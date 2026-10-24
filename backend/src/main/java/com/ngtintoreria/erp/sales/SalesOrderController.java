package com.ngtintoreria.erp.sales;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.crm.Client;
import com.ngtintoreria.erp.crm.ClientRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/orders")
public class SalesOrderController {
    private final SalesOrderRepository orders; private final SalesOrderItemRepository orderItems; private final SalesOrderEventRepository orderEvents;
    private final QuotationRepository quotations; private final QuotationItemRepository quoteItems; private final ClientRepository clients; private final AuditService audit;
    public SalesOrderController(SalesOrderRepository orders,SalesOrderItemRepository orderItems,SalesOrderEventRepository orderEvents,QuotationRepository quotations,QuotationItemRepository quoteItems,ClientRepository clients,AuditService audit){this.orders=orders;this.orderItems=orderItems;this.orderEvents=orderEvents;this.quotations=quotations;this.quoteItems=quoteItems;this.clients=clients;this.audit=audit;}
    public record ConvertRequest(String priority,String notes){} public record StatusRequest(String status,String comment){}
    public record OrderDto(SalesOrder header,String clientName,String quoteNumber,List<SalesOrderItem> items,List<SalesOrderEvent> events){}

    @GetMapping @PreAuthorize("hasAuthority('ORDERS_READ')") public List<OrderDto> list(){return orders.findAll().stream().sorted(Comparator.comparing(SalesOrder::getCreatedAt).reversed()).map(this::dto).toList();}
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('ORDERS_READ')") public OrderDto one(@PathVariable UUID id){return dto(orders.findById(id).orElseThrow());}

    @PostMapping("/from-quotation/{quoteId}") @Transactional @PreAuthorize("hasAuthority('ORDERS_WRITE')")
    public OrderDto fromQuotation(@PathVariable UUID quoteId,@RequestBody(required=false) ConvertRequest request,Authentication auth){
        Quotation q=quotations.findById(quoteId).orElseThrow(); if(!"APPROVED".equals(q.getStatus()))throw new IllegalArgumentException("La cotización debe estar aprobada antes de convertirla a pedido");
        Optional<SalesOrder> existing=orders.findByQuotationId(quoteId); if(existing.isPresent())return dto(existing.get());
        SalesOrder o=new SalesOrder(); o.setOrderNumber("PED-"+String.format("%06d",orders.nextNumber())); o.setQuotationId(q.getId()); o.setClientId(q.getClientId()); o.setStatus("NEW"); o.setPriority(priority(request==null?null:request.priority())); o.setRequestedDate(q.getRequestedDate()); o.setCommercialNotes(request==null||request.notes()==null?q.getNotes():request.notes()); o.setTotal(q.getTotal()); o.setCurrency(q.getCurrency()); o.setCreatedBy(auth.getName()); o=orders.save(o);
        for(QuotationItem source:quoteItems.findByQuotationIdOrderByLineNo(q.getId())){SalesOrderItem x=new SalesOrderItem(); x.setSalesOrderId(o.getId()); x.setQuotationItemId(source.getId()); x.setLineNo(source.getLineNo()); x.setFabricId(source.getFabricId()); x.setColorId(source.getColorId()); x.setProcessId(source.getProcessId()); x.setFinishId(source.getFinishId()); x.setFormulaId(source.getFormulaId()); x.setDescription(source.getDescription()); x.setQuantity(source.getQuantity()); x.setBillableQuantity(source.getBillableQuantity()); x.setUnit(source.getUnit()); x.setUnitPrice(source.getUnitPrice()); x.setLineTotal(source.getLineTotal()); orderItems.save(x);}
        addEvent(o,null,"NEW","Pedido creado desde "+q.getQuoteNumber(),auth.getName()); audit.record("PEDIDO_CREADO","Pedidos",o.getId().toString(),o.getOrderNumber()+" desde "+q.getQuoteNumber()); return dto(o);
    }

    @PostMapping("/{id}/status") @Transactional @PreAuthorize("hasAuthority('ORDERS_WRITE')")
    public OrderDto status(@PathVariable UUID id,@RequestBody StatusRequest request,Authentication auth){
        SalesOrder o=orders.findById(id).orElseThrow(); String target=normal(request.status()),from=o.getStatus();
        Map<String,Set<String>> allowed=Map.of("NEW",Set.of("CONFIRMED","CANCELLED"),"CONFIRMED",Set.of("ON_HOLD","READY_FOR_RECEPTION","CANCELLED"),"ON_HOLD",Set.of("CONFIRMED","CANCELLED"),"READY_FOR_RECEPTION",Set.of("ON_HOLD"),"PARTIALLY_RECEIVED",Set.of(),"RECEIVED",Set.of(),"CANCELLED",Set.of());
        if(!allowed.getOrDefault(from,Set.of()).contains(target))throw new IllegalArgumentException("Cambio de estado no permitido: "+from+" → "+target);
        o.setStatus(target); if("CONFIRMED".equals(target)&&o.getConfirmedAt()==null)o.setConfirmedAt(OffsetDateTime.now()); o=orders.save(o); addEvent(o,from,target,request.comment(),auth.getName()); audit.record("ESTADO_PEDIDO","Pedidos",id.toString(),o.getOrderNumber()+" · "+from+" → "+target); return dto(o);
    }
    private void addEvent(SalesOrder o,String from,String to,String comment,String actor){SalesOrderEvent e=new SalesOrderEvent();e.setSalesOrderId(o.getId());e.setFromStatus(from);e.setToStatus(to);e.setComment(comment);e.setActor(actor);orderEvents.save(e);}
    private OrderDto dto(SalesOrder o){String client=clients.findById(o.getClientId()).map(Client::getName).orElse("Cliente no disponible");String quote=quotations.findById(o.getQuotationId()).map(Quotation::getQuoteNumber).orElse("—");return new OrderDto(o,client,quote,orderItems.findBySalesOrderIdOrderByLineNo(o.getId()),orderEvents.findBySalesOrderIdOrderByCreatedAt(o.getId()));}
    private static String priority(String x){String p=normal(x);return Set.of("NORMAL","HIGH","URGENT").contains(p)?p:"NORMAL";} private static String normal(String x){return x==null?"":x.trim().toUpperCase(Locale.ROOT);}
}
