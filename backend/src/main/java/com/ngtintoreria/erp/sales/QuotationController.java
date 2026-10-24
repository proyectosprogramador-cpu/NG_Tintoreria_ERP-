package com.ngtintoreria.erp.sales;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.crm.Client;
import com.ngtintoreria.erp.crm.ClientRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/quotations")
public class QuotationController {
    private final QuotationRepository quotations;
    private final QuotationItemRepository items;
    private final QuotationEventRepository events;
    private final QuotationDeliveryRepository deliveries;
    private final SalesOrderRepository orders;
    private final ClientRepository clients;
    private final AuditService audit;

    public QuotationController(QuotationRepository quotations, QuotationItemRepository items,
                               QuotationEventRepository events, QuotationDeliveryRepository deliveries,
                               SalesOrderRepository orders, ClientRepository clients, AuditService audit) {
        this.quotations=quotations; this.items=items; this.events=events; this.deliveries=deliveries;
        this.orders=orders; this.clients=clients; this.audit=audit;
    }

    public record ItemRequest(UUID fabricId, UUID colorId, UUID processId, UUID finishId, UUID formulaId,
                              String description, BigDecimal quantity, BigDecimal billableQuantity, String unit,
                              BigDecimal unitPrice, BigDecimal surchargePct, BigDecimal discountPct, String priceSource) {}
    public record QuotationRequest(UUID clientId, String currency, LocalDate validUntil, LocalDate requestedDate,
                                   String paymentTerms, BigDecimal taxRate, BigDecimal discountPct,
                                   String notes, Integer versionNo, List<ItemRequest> items) {}
    public record StatusRequest(String status,String comment) {}
    public record DeliveryRequest(String channel,String destination,String detail) {}
    public record QuotationDto(Quotation header,String clientName,List<QuotationItem> items,
                               List<QuotationEvent> events,List<QuotationDelivery> deliveries,UUID orderId) {}

    @GetMapping
    @PreAuthorize("hasAuthority('QUOTES_READ')")
    public List<QuotationDto> list(){return quotations.findAll().stream().sorted(Comparator.comparing(Quotation::getCreatedAt).reversed()).map(this::dto).toList();}

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('QUOTES_READ')")
    public QuotationDto one(@PathVariable UUID id){return dto(quotations.findById(id).orElseThrow());}

    @PostMapping
    @Transactional
    @PreAuthorize("hasAuthority('QUOTES_WRITE')")
    public QuotationDto create(@RequestBody QuotationRequest request,Authentication auth){
        validate(request); Client client=clients.findById(request.clientId()).orElseThrow();
        Quotation q=new Quotation(); q.setQuoteNumber("COT-"+String.format("%06d",quotations.nextNumber()));
        q.setCreatedBy(auth.getName()); apply(q,request); calculate(q,saveItems(q,request.items())); q=quotations.save(q);
        addEvent(q,null,"DRAFT","Cotización creada",auth.getName());
        audit.record("COTIZACION_CREADA","Cotizaciones",q.getId().toString(),q.getQuoteNumber()+" · "+client.getName());
        return dto(q);
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('QUOTES_WRITE')")
    public QuotationDto update(@PathVariable UUID id,@RequestBody QuotationRequest request,Authentication auth){
        validate(request); Quotation q=quotations.findById(id).orElseThrow();
        if(!"DRAFT".equals(q.getStatus()))throw new IllegalArgumentException("Solo una cotización en borrador puede editarse");
        if(request.versionNo()!=null&&request.versionNo()!=q.getVersionNo())throw new IllegalArgumentException("La cotización fue modificada por otro usuario. Recargue antes de guardar");
        clients.findById(request.clientId()).orElseThrow(); apply(q,request); q.setVersionNo(q.getVersionNo()+1);
        items.deleteByQuotationId(q.getId()); calculate(q,saveItems(q,request.items())); q=quotations.save(q);
        audit.record("COTIZACION_ACTUALIZADA","Cotizaciones",id.toString(),q.getQuoteNumber()+" V"+q.getVersionNo());
        return dto(q);
    }

    @PostMapping("/{id}/status")
    @Transactional
    @PreAuthorize("hasAuthority('QUOTES_WRITE') or hasAuthority('QUOTES_APPROVE')")
    public QuotationDto status(@PathVariable UUID id,@RequestBody StatusRequest request,Authentication auth){
        Quotation q=quotations.findById(id).orElseThrow(); String target=normal(request.status());
        changeStatus(q,target,request.comment(),auth); return dto(q);
    }

    @PostMapping("/{id}/deliveries")
    @Transactional
    @PreAuthorize("hasAuthority('QUOTES_WRITE')")
    public QuotationDto registerDelivery(@PathVariable UUID id,@RequestBody DeliveryRequest request,Authentication auth){
        Quotation q=quotations.findById(id).orElseThrow(); String channel=normal(request.channel());
        if(!Set.of("WHATSAPP","EMAIL","PRINT","OTHER").contains(channel))throw new IllegalArgumentException("Canal de envío inválido");
        if(request.destination()==null||request.destination().isBlank())throw new IllegalArgumentException("Indique el destino del envío");
        if("IN_REVIEW".equals(q.getStatus()))changeStatus(q,"SENT","Proforma enviada por "+channel,auth);
        else if(!"SENT".equals(q.getStatus()))throw new IllegalArgumentException("La cotización debe estar en revisión o enviada");
        QuotationDelivery d=new QuotationDelivery(); d.setQuotationId(id); d.setChannel(channel); d.setDestination(request.destination().trim()); d.setDetail(request.detail()); d.setSentBy(auth.getName()); deliveries.save(d);
        audit.record("PROFORMA_ENVIADA","Cotizaciones",id.toString(),q.getQuoteNumber()+" · "+channel+" · "+d.getDestination()); return dto(q);
    }

    private void changeStatus(Quotation q,String target,String comment,Authentication auth){
        Map<String,Set<String>> allowed=Map.of(
                "DRAFT",Set.of("IN_REVIEW","CANCELLED"),
                "IN_REVIEW",Set.of("DRAFT","SENT","CANCELLED"),
                "SENT",Set.of("APPROVED","REJECTED","EXPIRED","CANCELLED"),
                "REJECTED",Set.of("DRAFT"),
                "APPROVED",Set.of(),"EXPIRED",Set.of(),"CANCELLED",Set.of());
        String from=q.getStatus();
        if(!allowed.getOrDefault(from,Set.of()).contains(target))throw new IllegalArgumentException("Cambio de estado no permitido: "+from+" → "+target);
        if("SENT".equals(target)&&q.getDiscountPct().compareTo(new BigDecimal("5"))>0&&!isAdmin(auth))
            throw new IllegalArgumentException("Un descuento mayor a 5% debe ser autorizado por el Administrador");
        q.setStatus(target); if("SENT".equals(target))q.setSentAt(OffsetDateTime.now()); if("APPROVED".equals(target))q.setApprovedAt(OffsetDateTime.now()); quotations.save(q);
        addEvent(q,from,target,comment,auth.getName()); audit.record("ESTADO_COTIZACION","Cotizaciones",q.getId().toString(),q.getQuoteNumber()+" · "+from+" → "+target);
    }

    private void apply(Quotation q,QuotationRequest r){q.setClientId(r.clientId()); q.setCurrency(blank(r.currency())?"PEN":normal(r.currency())); q.setValidUntil(r.validUntil()); q.setRequestedDate(r.requestedDate()); q.setPaymentTerms(r.paymentTerms()); q.setTaxRate(nvl(r.taxRate(),new BigDecimal("18"))); q.setDiscountPct(nvl(r.discountPct(),BigDecimal.ZERO)); q.setNotes(r.notes());}
    private List<QuotationItem> saveItems(Quotation q,List<ItemRequest> requests){
        if(q.getId()==null)q=quotations.save(q); List<QuotationItem> saved=new ArrayList<>(); int line=1;
        for(ItemRequest r:requests){
            if(r.fabricId()==null||r.colorId()==null||r.processId()==null)throw new IllegalArgumentException("Cada partida requiere tela, color y proceso");
            BigDecimal quantity=nvl(r.quantity(),BigDecimal.ZERO), billable=nvl(r.billableQuantity(),quantity), price=nvl(r.unitPrice(),BigDecimal.ZERO);
            if(quantity.signum()<=0||billable.signum()<=0||price.signum()<0)throw new IllegalArgumentException("Cantidad y precio inválidos en la partida "+line);
            if(billable.compareTo(quantity)<0)throw new IllegalArgumentException("La cantidad facturable no puede ser menor que la cantidad real en la partida "+line);
            BigDecimal surcharge=nvl(r.surchargePct(),BigDecimal.ZERO), lineDiscount=nvl(r.discountPct(),BigDecimal.ZERO);
            if(surcharge.signum()<0||lineDiscount.signum()<0||lineDiscount.compareTo(new BigDecimal("100"))>0)
                throw new IllegalArgumentException("Recargo o descuento inválido en la partida "+line);
            QuotationItem x=new QuotationItem(); x.setQuotationId(q.getId()); x.setLineNo(line++); x.setFabricId(r.fabricId()); x.setColorId(r.colorId()); x.setProcessId(r.processId()); x.setFinishId(r.finishId()); x.setFormulaId(r.formulaId()); x.setDescription(r.description()); x.setQuantity(quantity); x.setBillableQuantity(billable); x.setUnit(blank(r.unit())?"kg":r.unit().trim()); x.setUnitPrice(price); x.setSurchargePct(nvl(r.surchargePct(),BigDecimal.ZERO)); x.setDiscountPct(nvl(r.discountPct(),BigDecimal.ZERO)); x.setPriceSource(r.priceSource());
            BigDecimal lineSubtotal=billable.multiply(price).multiply(BigDecimal.ONE.add(x.getSurchargePct().divide(new BigDecimal("100"),8,RoundingMode.HALF_UP)));
            BigDecimal lineTotal=lineSubtotal.multiply(BigDecimal.ONE.subtract(x.getDiscountPct().divide(new BigDecimal("100"),8,RoundingMode.HALF_UP)));
            x.setLineSubtotal(money(lineSubtotal)); x.setLineTotal(money(lineTotal)); saved.add(items.save(x));
        }
        return saved;
    }
    private void calculate(Quotation q,List<QuotationItem> lines){BigDecimal subtotal=lines.stream().map(QuotationItem::getLineTotal).reduce(BigDecimal.ZERO,BigDecimal::add); BigDecimal discount=subtotal.multiply(q.getDiscountPct()).divide(new BigDecimal("100"),8,RoundingMode.HALF_UP); BigDecimal taxable=subtotal.subtract(discount); BigDecimal tax=taxable.multiply(q.getTaxRate()).divide(new BigDecimal("100"),8,RoundingMode.HALF_UP); q.setSubtotal(money(subtotal)); q.setDiscountAmount(money(discount)); q.setTaxAmount(money(tax)); q.setTotal(money(taxable.add(tax))); quotations.save(q);}
    private void validate(QuotationRequest r){if(r==null||r.clientId()==null)throw new IllegalArgumentException("Seleccione un cliente"); if(r.items()==null||r.items().isEmpty())throw new IllegalArgumentException("Agregue al menos una partida"); BigDecimal discount=nvl(r.discountPct(),BigDecimal.ZERO),tax=nvl(r.taxRate(),new BigDecimal("18")); if(discount.signum()<0||discount.compareTo(new BigDecimal("100"))>0)throw new IllegalArgumentException("Descuento general inválido"); if(tax.signum()<0||tax.compareTo(new BigDecimal("100"))>0)throw new IllegalArgumentException("Tasa de impuesto inválida");}
    private void addEvent(Quotation q,String from,String to,String comment,String actor){QuotationEvent e=new QuotationEvent(); e.setQuotationId(q.getId()); e.setFromStatus(from); e.setToStatus(to); e.setComment(comment); e.setActor(actor); events.save(e);}
    private QuotationDto dto(Quotation q){String client=clients.findById(q.getClientId()).map(Client::getName).orElse("Cliente no disponible"); UUID orderId=orders.findByQuotationId(q.getId()).map(SalesOrder::getId).orElse(null); return new QuotationDto(q,client,items.findByQuotationIdOrderByLineNo(q.getId()),events.findByQuotationIdOrderByCreatedAt(q.getId()),deliveries.findByQuotationIdOrderByCreatedAt(q.getId()),orderId);}
    private static BigDecimal nvl(BigDecimal x,BigDecimal fallback){return x==null?fallback:x;} private static BigDecimal money(BigDecimal x){return x.setScale(2,RoundingMode.HALF_UP);} private static boolean blank(String x){return x==null||x.isBlank();} private static String normal(String x){return x==null?"":x.trim().toUpperCase(Locale.ROOT);} private static boolean isAdmin(Authentication a){return a.getAuthorities().stream().anyMatch(x->"ROLE_ADMIN".equals(x.getAuthority()));}
}
