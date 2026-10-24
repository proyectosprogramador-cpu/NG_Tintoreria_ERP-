package com.ngtintoreria.erp.receiving;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.crm.Client;
import com.ngtintoreria.erp.crm.ClientRepository;
import com.ngtintoreria.erp.sales.*;
import jakarta.transaction.Transactional;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/receptions")
public class ReceptionController {
    private static final Set<String> EDITABLE=Set.of("DRAFT","IN_INSPECTION","OBSERVED");
    private final FabricReceptionRepository receptions;
    private final ReceptionRollRepository rolls;
    private final ReceptionEventRepository events;
    private final ReceptionAttachmentRepository attachments;
    private final ReceptionStorageService storage;
    private final SalesOrderRepository orders;
    private final SalesOrderItemRepository orderItems;
    private final SalesOrderEventRepository orderEvents;
    private final ClientRepository clients;
    private final AuditService audit;

    public ReceptionController(FabricReceptionRepository receptions,ReceptionRollRepository rolls,ReceptionEventRepository events,
                               ReceptionAttachmentRepository attachments,ReceptionStorageService storage,SalesOrderRepository orders,
                               SalesOrderItemRepository orderItems,SalesOrderEventRepository orderEvents,ClientRepository clients,AuditService audit){
        this.receptions=receptions;this.rolls=rolls;this.events=events;this.attachments=attachments;this.storage=storage;
        this.orders=orders;this.orderItems=orderItems;this.orderEvents=orderEvents;this.clients=clients;this.audit=audit;
    }

    public record RollRequest(UUID id,UUID salesOrderItemId,String clientRollCode,String ownership,BigDecimal declaredKg,
                              BigDecimal grossKg,BigDecimal tareKg,BigDecimal meters,BigDecimal widthCm,BigDecimal gsm,
                              String inspectionStatus,String defectDescription,String observations){}
    public record ReceptionRequest(UUID salesOrderId,LocalDate receivedDate,String clientGuideNumber,String vehiclePlate,
                                   String deliveredBy,String receivedBy,String warehouseLocation,String notes,Integer versionNo,
                                   List<RollRequest> rolls){}
    public record StatusRequest(String status,String comment){}
    public record AttachmentDto(UUID id,UUID rollId,String originalName,String contentType,long sizeBytes,String uploadedBy,OffsetDateTime createdAt){}
    public record Reconciliation(UUID salesOrderItemId,int lineNo,String unit,BigDecimal orderedQuantity,
                                 BigDecimal previouslyReleased,BigDecimal currentQuantity,BigDecimal projectedTotal,BigDecimal remainingQuantity){}
    public record ReceptionDto(FabricReception header,String clientName,String orderNumber,List<ReceptionRoll> rolls,
                               List<AttachmentDto> attachments,List<ReceptionEvent> events,List<Reconciliation> reconciliation){}

    @GetMapping
    @PreAuthorize("hasAuthority('RECEPTIONS_READ')")
    public List<ReceptionDto> list(){
        return receptions.findAll().stream().sorted(Comparator.comparing(FabricReception::getCreatedAt).reversed()).map(this::dto).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('RECEPTIONS_READ')")
    public ReceptionDto one(@PathVariable UUID id){return dto(findReception(id));}

    @PostMapping
    @Transactional
    @PreAuthorize("hasAuthority('RECEPTIONS_WRITE')")
    public ReceptionDto create(@RequestBody ReceptionRequest request,Authentication auth){
        validateHeader(request);
        SalesOrder order=findEligibleOrder(request.salesOrderId());
        FabricReception reception=new FabricReception();
        reception.setReceptionNumber("REC-"+String.format("%06d",receptions.nextNumber()));
        reception.setSalesOrderId(order.getId());reception.setClientId(order.getClientId());reception.setStatus("DRAFT");
        reception.setCreatedBy(auth.getName());copyHeader(request,reception);
        reception=receptions.save(reception);
        saveRolls(reception,request.rolls(),order);
        recalculate(reception);reception=receptions.save(reception);
        addEvent(reception,null,"DRAFT","Recepción registrada",auth.getName());
        audit.record("RECEPCION_CREADA","Recepciones",reception.getId().toString(),reception.getReceptionNumber()+" · "+order.getOrderNumber());
        return dto(reception);
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('RECEPTIONS_WRITE')")
    public ReceptionDto update(@PathVariable UUID id,@RequestBody ReceptionRequest request,Authentication auth){
        validateHeader(request);
        FabricReception reception=findReception(id);
        if(!EDITABLE.contains(reception.getStatus()))throw new IllegalArgumentException("La recepción ya no admite cambios");
        if(!Objects.equals(reception.getSalesOrderId(),request.salesOrderId()))throw new IllegalArgumentException("El pedido de una recepción no puede cambiarse");
        if(request.versionNo()==null||request.versionNo()!=reception.getVersionNo())throw new IllegalArgumentException("La recepción fue modificada por otro usuario; actualice la pantalla antes de continuar");
        SalesOrder order=orders.findById(reception.getSalesOrderId()).orElseThrow();
        copyHeader(request,reception);reception.setVersionNo(reception.getVersionNo()+1);
        saveRolls(reception,request.rolls(),order);
        recalculate(reception);reception=receptions.save(reception);
        audit.record("RECEPCION_ACTUALIZADA","Recepciones",id.toString(),reception.getReceptionNumber()+" · versión "+reception.getVersionNo());
        return dto(reception);
    }

    @PostMapping("/{id}/status")
    @Transactional
    @PreAuthorize("hasAuthority('RECEPTIONS_WRITE')")
    public ReceptionDto status(@PathVariable UUID id,@RequestBody StatusRequest request,Authentication auth){
        FabricReception reception=findReception(id);String from=reception.getStatus(),target=normal(request.status());
        Map<String,Set<String>> allowed=Map.of(
                "DRAFT",Set.of("IN_INSPECTION","CANCELLED"),
                "IN_INSPECTION",Set.of("DRAFT","OBSERVED","RELEASED","CANCELLED"),
                "OBSERVED",Set.of("IN_INSPECTION","CANCELLED"),
                "RELEASED",Set.of(),"CANCELLED",Set.of());
        if(!allowed.getOrDefault(from,Set.of()).contains(target))throw new IllegalArgumentException("Cambio de estado no permitido: "+from+" → "+target);
        List<ReceptionRoll> receptionRolls=rolls.findByReceptionIdOrderByLineNo(id);
        if("IN_INSPECTION".equals(target)&&receptionRolls.isEmpty())throw new IllegalArgumentException("Registre al menos un rollo antes de iniciar la inspección");
        if("OBSERVED".equals(target)&&receptionRolls.stream().noneMatch(r->Set.of("OBSERVED","REJECTED").contains(r.getInspectionStatus())))throw new IllegalArgumentException("Marque al menos un rollo como observado o rechazado");
        if("RELEASED".equals(target)){
            requireAuthority(auth,"RECEPTIONS_RELEASE");
            if(receptionRolls.isEmpty())throw new IllegalArgumentException("No se puede liberar una recepción sin rollos");
            if(receptionRolls.stream().anyMatch(r->!"INSPECTED".equals(r.getInspectionStatus())))throw new IllegalArgumentException("Todos los rollos deben estar inspeccionados y sin observaciones antes de liberar");
            SalesOrder order=orders.findById(reception.getSalesOrderId()).orElseThrow();
            Map<UUID,SalesOrderItem> itemMap=orderItems.findBySalesOrderIdOrderByLineNo(order.getId()).stream().collect(Collectors.toMap(SalesOrderItem::getId,Function.identity()));
            receptionRolls.forEach(r->validateMeasurements(r,itemMap.get(r.getSalesOrderItemId())));
            reception.setReleasedAt(OffsetDateTime.now());
        }
        reception.setStatus(target);reception.setVersionNo(reception.getVersionNo()+1);reception=receptions.save(reception);
        addEvent(reception,from,target,blankToNull(request.comment()),auth.getName());
        if("RELEASED".equals(target))updateOrderFulfillment(reception.getSalesOrderId(),reception.getReceptionNumber(),auth.getName());
        audit.record("ESTADO_RECEPCION","Recepciones",id.toString(),reception.getReceptionNumber()+" · "+from+" → "+target);
        return dto(reception);
    }

    @PostMapping(path="/{id}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    @PreAuthorize("hasAuthority('RECEPTIONS_WRITE')")
    public AttachmentDto upload(@PathVariable UUID id,@RequestParam(required=false) UUID rollId,@RequestPart("file") MultipartFile file,Authentication auth){
        FabricReception reception=findReception(id);if(!EDITABLE.contains(reception.getStatus()))throw new IllegalArgumentException("No se pueden agregar evidencias a una recepción cerrada");
        if(rollId!=null){ReceptionRoll roll=rolls.findById(rollId).orElseThrow();if(!roll.getReceptionId().equals(id))throw new IllegalArgumentException("El rollo no pertenece a esta recepción");}
        ReceptionStorageService.StoredFile saved=storage.save(file);
        ReceptionAttachment attachment=new ReceptionAttachment();attachment.setReceptionId(id);attachment.setRollId(rollId);attachment.setOriginalName(saved.originalName());attachment.setContentType(saved.contentType());attachment.setSizeBytes(saved.sizeBytes());attachment.setStorageName(saved.storageName());attachment.setUploadedBy(auth.getName());
        attachment=attachments.save(attachment);audit.record("EVIDENCIA_AGREGADA","Recepciones",id.toString(),reception.getReceptionNumber()+" · "+attachment.getOriginalName());return attachmentDto(attachment);
    }

    @GetMapping("/attachments/{attachmentId}/file")
    @PreAuthorize("hasAuthority('RECEPTIONS_READ')")
    public ResponseEntity<Resource> download(@PathVariable UUID attachmentId){
        ReceptionAttachment attachment=attachments.findById(attachmentId).orElseThrow();Resource resource=storage.load(attachment.getStorageName());
        ContentDisposition disposition=ContentDisposition.attachment().filename(attachment.getOriginalName(),StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(attachment.getContentType())).contentLength(attachment.getSizeBytes()).header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).body(resource);
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @Transactional
    @PreAuthorize("hasAuthority('RECEPTIONS_WRITE')")
    public void deleteAttachment(@PathVariable UUID attachmentId){
        ReceptionAttachment attachment=attachments.findById(attachmentId).orElseThrow();FabricReception reception=findReception(attachment.getReceptionId());
        if(!EDITABLE.contains(reception.getStatus()))throw new IllegalArgumentException("No se pueden eliminar evidencias de una recepción cerrada");
        attachments.delete(attachment);storage.delete(attachment.getStorageName());audit.record("EVIDENCIA_ELIMINADA","Recepciones",reception.getId().toString(),reception.getReceptionNumber()+" · "+attachment.getOriginalName());
    }

    private FabricReception findReception(UUID id){return receptions.findById(id).orElseThrow();}
    private SalesOrder findEligibleOrder(UUID id){
        if(id==null)throw new IllegalArgumentException("Seleccione el pedido que se recibirá");SalesOrder order=orders.findById(id).orElseThrow();
        if(!Set.of("READY_FOR_RECEPTION","PARTIALLY_RECEIVED").contains(order.getStatus()))throw new IllegalArgumentException("El pedido debe estar habilitado para recepción");
        return order;
    }
    private void validateHeader(ReceptionRequest request){
        if(request==null)throw new IllegalArgumentException("Complete los datos de recepción");
        if(request.receivedDate()==null)throw new IllegalArgumentException("Indique la fecha de recepción");
        if(request.receivedDate().isAfter(LocalDate.now(ZoneId.of("America/Lima"))))throw new IllegalArgumentException("La fecha de recepción no puede ser futura");
        if(blankToNull(request.receivedBy())==null)throw new IllegalArgumentException("Indique quién recibe la tela");
        if(request.rolls()==null||request.rolls().isEmpty())throw new IllegalArgumentException("Registre al menos un rollo");
        if(request.rolls().size()>500)throw new IllegalArgumentException("Una recepción no puede contener más de 500 rollos");
    }
    private void copyHeader(ReceptionRequest request,FabricReception r){
        r.setReceivedDate(request.receivedDate());r.setClientGuideNumber(trim(request.clientGuideNumber(),80));r.setVehiclePlate(trim(request.vehiclePlate(),20));
        r.setDeliveredBy(trim(request.deliveredBy(),180));r.setReceivedBy(trim(request.receivedBy(),180));r.setWarehouseLocation(trim(request.warehouseLocation(),120));r.setNotes(trim(request.notes(),4000));
    }
    private void saveRolls(FabricReception reception,List<RollRequest> requestRolls,SalesOrder order){
        Map<UUID,SalesOrderItem> itemMap=orderItems.findBySalesOrderIdOrderByLineNo(order.getId()).stream().collect(Collectors.toMap(SalesOrderItem::getId,Function.identity()));
        Map<UUID,ReceptionRoll> existing=rolls.findByReceptionIdOrderByLineNo(reception.getId()).stream().collect(Collectors.toMap(ReceptionRoll::getId,Function.identity()));
        Set<UUID> retained=new HashSet<>();int line=0;
        for(RollRequest request:requestRolls){
            line++;SalesOrderItem item=itemMap.get(request.salesOrderItemId());if(item==null)throw new IllegalArgumentException("El rollo "+line+" no corresponde a una partida del pedido");
            ReceptionRoll roll;
            if(request.id()!=null){roll=existing.get(request.id());if(roll==null)throw new IllegalArgumentException("El rollo "+line+" no pertenece a esta recepción");retained.add(roll.getId());}
            else{roll=new ReceptionRoll();roll.setReceptionId(reception.getId());roll.setRollCode("ROL-"+String.format("%08d",rolls.nextNumber()));}
            roll.setSalesOrderItemId(item.getId());roll.setFabricId(item.getFabricId());roll.setLineNo(line);roll.setClientRollCode(trim(request.clientRollCode(),80));
            String ownership=normal(request.ownership());if(!Set.of("CLIENT","COMPANY","THIRD_PARTY").contains(ownership))ownership="CLIENT";roll.setOwnership(ownership);
            roll.setDeclaredKg(nonNegative(request.declaredKg(),"Peso declarado",line));roll.setGrossKg(nonNegative(request.grossKg(),"Peso bruto",line));roll.setTareKg(nonNegative(request.tareKg(),"Tara",line));
            if(roll.getGrossKg().compareTo(roll.getTareKg())<0)throw new IllegalArgumentException("En el rollo "+line+", el peso bruto no puede ser menor que la tara");
            roll.setNetKg(roll.getGrossKg().subtract(roll.getTareKg()));roll.setMeters(nonNegative(request.meters(),"Metros",line));roll.setWidthCm(positiveOrNull(request.widthCm(),"Ancho",line));roll.setGsm(positiveOrNull(request.gsm(),"Gramaje",line));
            String inspection=normal(request.inspectionStatus());if(!Set.of("PENDING","INSPECTED","OBSERVED","REJECTED").contains(inspection))inspection="PENDING";roll.setInspectionStatus(inspection);
            roll.setDefectDescription(trim(request.defectDescription(),500));roll.setObservations(trim(request.observations(),500));
            if(Set.of("OBSERVED","REJECTED").contains(inspection)&&blankToNull(roll.getDefectDescription())==null)throw new IllegalArgumentException("Describa el defecto del rollo "+line);
            validateMeasurements(roll,item);rolls.save(roll);
        }
        for(ReceptionRoll old:existing.values())if(!retained.contains(old.getId())){
            if(attachments.existsByRollId(old.getId()))throw new IllegalArgumentException("No puede eliminar el rollo "+old.getRollCode()+" porque tiene evidencias adjuntas");
            rolls.delete(old);
        }
    }
    private void validateMeasurements(ReceptionRoll roll,SalesOrderItem item){
        if(item==null)throw new IllegalArgumentException("La partida asociada al rollo ya no existe");String unit=normal(item.getUnit());
        if("KG".equals(unit)&&roll.getNetKg().compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("El rollo "+roll.getLineNo()+" requiere un peso neto mayor que cero porque el pedido está en kg");
        if("M".equals(unit)&&roll.getMeters().compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("El rollo "+roll.getLineNo()+" requiere metros mayores que cero porque el pedido está en m");
        if("SERVICIO".equals(unit))throw new IllegalArgumentException("Una partida por servicio no admite recepción física de rollos");
    }
    private void recalculate(FabricReception reception){
        List<ReceptionRoll> list=rolls.findByReceptionIdOrderByLineNo(reception.getId());reception.setRollCount(list.size());
        reception.setTotalDeclaredKg(sum(list,ReceptionRoll::getDeclaredKg));reception.setTotalGrossKg(sum(list,ReceptionRoll::getGrossKg));reception.setTotalTareKg(sum(list,ReceptionRoll::getTareKg));reception.setTotalNetKg(sum(list,ReceptionRoll::getNetKg));reception.setTotalMeters(sum(list,ReceptionRoll::getMeters));
    }
    private void updateOrderFulfillment(UUID orderId,String receptionNumber,String actor){
        SalesOrder order=orders.findById(orderId).orElseThrow();String from=order.getStatus();List<SalesOrderItem> items=orderItems.findBySalesOrderIdOrderByLineNo(orderId);
        List<FabricReception> released=receptions.findBySalesOrderId(orderId).stream().filter(r->"RELEASED".equals(r.getStatus())).toList();
        Map<UUID,BigDecimal> received=new HashMap<>();for(FabricReception r:released)for(ReceptionRoll roll:rolls.findByReceptionIdOrderByLineNo(r.getId()))received.merge(roll.getSalesOrderItemId(),measurement(roll,items.stream().filter(i->i.getId().equals(roll.getSalesOrderItemId())).findFirst().orElse(null)),BigDecimal::add);
        boolean complete=items.stream().allMatch(i->received.getOrDefault(i.getId(),BigDecimal.ZERO).compareTo(i.getQuantity())>=0);
        String target=complete?"RECEIVED":"PARTIALLY_RECEIVED";if(!target.equals(from)){order.setStatus(target);orders.save(order);SalesOrderEvent event=new SalesOrderEvent();event.setSalesOrderId(orderId);event.setFromStatus(from);event.setToStatus(target);event.setComment("Recepción liberada: "+receptionNumber);event.setActor(actor);orderEvents.save(event);}
    }
    private ReceptionDto dto(FabricReception reception){
        SalesOrder order=orders.findById(reception.getSalesOrderId()).orElseThrow();String client=clients.findById(reception.getClientId()).map(Client::getName).orElse("Cliente no disponible");
        List<ReceptionRoll> current=rolls.findByReceptionIdOrderByLineNo(reception.getId());List<Reconciliation> reconciliation=reconciliation(reception,order,current);
        return new ReceptionDto(reception,client,order.getOrderNumber(),current,attachments.findByReceptionIdOrderByCreatedAt(reception.getId()).stream().map(this::attachmentDto).toList(),events.findByReceptionIdOrderByCreatedAt(reception.getId()),reconciliation);
    }
    private List<Reconciliation> reconciliation(FabricReception currentReception,SalesOrder order,List<ReceptionRoll> currentRolls){
        List<FabricReception> otherReleased=receptions.findBySalesOrderId(order.getId()).stream().filter(r->"RELEASED".equals(r.getStatus())&&!r.getId().equals(currentReception.getId())).toList();
        Map<UUID,BigDecimal> previous=new HashMap<>();List<SalesOrderItem> items=orderItems.findBySalesOrderIdOrderByLineNo(order.getId());Map<UUID,SalesOrderItem> itemMap=items.stream().collect(Collectors.toMap(SalesOrderItem::getId,Function.identity()));
        for(FabricReception r:otherReleased)for(ReceptionRoll roll:rolls.findByReceptionIdOrderByLineNo(r.getId()))previous.merge(roll.getSalesOrderItemId(),measurement(roll,itemMap.get(roll.getSalesOrderItemId())),BigDecimal::add);
        Map<UUID,BigDecimal> current=new HashMap<>();for(ReceptionRoll roll:currentRolls)current.merge(roll.getSalesOrderItemId(),measurement(roll,itemMap.get(roll.getSalesOrderItemId())),BigDecimal::add);
        return items.stream().map(i->{BigDecimal p=previous.getOrDefault(i.getId(),BigDecimal.ZERO),c=current.getOrDefault(i.getId(),BigDecimal.ZERO),projected=p.add(c);return new Reconciliation(i.getId(),i.getLineNo(),i.getUnit(),i.getQuantity(),p,c,projected,i.getQuantity().subtract(projected));}).toList();
    }
    private BigDecimal measurement(ReceptionRoll roll,SalesOrderItem item){
        if(item==null)return BigDecimal.ZERO;return switch(normal(item.getUnit())){case "KG"->roll.getNetKg();case "M"->roll.getMeters();case "ROLLO"->BigDecimal.ONE;default->BigDecimal.ZERO;};
    }
    private void addEvent(FabricReception r,String from,String to,String comment,String actor){ReceptionEvent e=new ReceptionEvent();e.setReceptionId(r.getId());e.setFromStatus(from);e.setToStatus(to);e.setComment(comment);e.setActor(actor);events.save(e);}
    private AttachmentDto attachmentDto(ReceptionAttachment a){return new AttachmentDto(a.getId(),a.getRollId(),a.getOriginalName(),a.getContentType(),a.getSizeBytes(),a.getUploadedBy(),a.getCreatedAt());}
    private void requireAuthority(Authentication auth,String authority){if(auth==null||auth.getAuthorities().stream().noneMatch(a->authority.equals(a.getAuthority())))throw new IllegalArgumentException("No tiene permiso para liberar recepciones");}
    private static BigDecimal sum(List<ReceptionRoll> rows,Function<ReceptionRoll,BigDecimal> fn){return rows.stream().map(fn).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static BigDecimal nonNegative(BigDecimal value,String field,int line){BigDecimal v=value==null?BigDecimal.ZERO:value;if(v.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException(field+" no puede ser negativo en el rollo "+line);return v;}
    private static BigDecimal positiveOrNull(BigDecimal value,String field,int line){if(value==null)return null;if(value.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException(field+" debe ser mayor que cero en el rollo "+line);return value;}
    private static String trim(String value,int max){String v=blankToNull(value);return v==null?null:(v.length()>max?v.substring(0,max):v);}
    private static String blankToNull(String value){return value==null||value.trim().isEmpty()?null:value.trim();}
    private static String normal(String value){return value==null?"":value.trim().toUpperCase(Locale.ROOT);}
}
