package com.ngtintoreria.erp.production;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.crm.*;
import com.ngtintoreria.erp.laboratory.*;
import com.ngtintoreria.erp.inventory.InventoryService;
import com.ngtintoreria.erp.maintenance.MaintenanceAvailabilityService;
import com.ngtintoreria.erp.masterdata.*;
import com.ngtintoreria.erp.receiving.*;
import com.ngtintoreria.erp.sales.*;
import jakarta.transaction.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/production-orders")
public class ProductionController {
 private static final Set<String> BUSY_MACHINE_STATES=Set.of("PLANNED","IN_PROCESS","PAUSED");
 private final ProductionOrderRepository productionOrders; private final ProductionCancellationRequestRepository cancellationRequests; private final ProductionOrderRollRepository productionRolls;
 private final ProductionMaterialRequirementRepository materials; private final ProductionOperationRepository operations;
 private final ProductionEventRepository events; private final ProductionPlanningService planning;
 private final ColorLabRequestRepository labRequests; private final LabRecipeVersionRepository labRecipes;
 private final LabRecipeComponentRepository labComponents; private final FormulaRepository formulas;
 private final FormulaVersionRepository formulaVersions; private final ReceptionRollRepository receptionRolls;
 private final FabricReceptionRepository receptions; private final SalesOrderRepository salesOrders;
 private final SalesOrderItemRepository salesItems; private final ClientRepository clients; private final MachineRepository machines;
 private final FabricRepository fabrics; private final DyeColorRepository colors; private final ProcessTypeRepository processes;
 private final FinishTypeRepository finishes; private final AuditService audit; private final InventoryService inventory; private final ProductionExecutionService execution; private final MaintenanceAvailabilityService maintenance;

 public ProductionController(ProductionOrderRepository productionOrders,ProductionCancellationRequestRepository cancellationRequests,ProductionOrderRollRepository productionRolls,
  ProductionMaterialRequirementRepository materials,ProductionOperationRepository operations,ProductionEventRepository events,
  ProductionPlanningService planning,ColorLabRequestRepository labRequests,LabRecipeVersionRepository labRecipes,
  LabRecipeComponentRepository labComponents,FormulaRepository formulas,FormulaVersionRepository formulaVersions,
  ReceptionRollRepository receptionRolls,FabricReceptionRepository receptions,SalesOrderRepository salesOrders,
  SalesOrderItemRepository salesItems,ClientRepository clients,MachineRepository machines,FabricRepository fabrics,
  DyeColorRepository colors,ProcessTypeRepository processes,FinishTypeRepository finishes,AuditService audit,InventoryService inventory,ProductionExecutionService execution,MaintenanceAvailabilityService maintenance){
  this.productionOrders=productionOrders;this.cancellationRequests=cancellationRequests;this.productionRolls=productionRolls;this.materials=materials;this.operations=operations;
  this.events=events;this.planning=planning;this.labRequests=labRequests;this.labRecipes=labRecipes;this.labComponents=labComponents;
  this.formulas=formulas;this.formulaVersions=formulaVersions;this.receptionRolls=receptionRolls;this.receptions=receptions;
  this.salesOrders=salesOrders;this.salesItems=salesItems;this.clients=clients;this.machines=machines;this.fabrics=fabrics;
  this.colors=colors;this.processes=processes;this.finishes=finishes;this.audit=audit;this.inventory=inventory;this.execution=execution;this.maintenance=maintenance;
 }

 public record RollOption(UUID id,String rollCode,String receptionNumber,BigDecimal netKg,BigDecimal meters){}
 public record EligibleBatch(UUID labRequestId,String labNumber,UUID recipeVersionId,int recipeVersion,String alternative,
  UUID formulaId,String formulaCode,UUID salesOrderId,String orderNumber,UUID salesOrderItemId,int orderLine,
  UUID clientId,String clientName,UUID fabricId,String fabricName,UUID colorId,String colorName,String colorFamily,
  UUID processId,String processName,UUID finishId,String finishName,BigDecimal bathRatio,Integer recipeTimeMinutes,
  BigDecimal remainingWeightKg,List<RollOption> rolls){}
 public record OperationRequest(String operationCode,String operationName,Integer plannedMinutes){}
 public record PlanRequest(UUID labRequestId,UUID machineId,List<UUID> rollIds,BigDecimal manualBatchWeightKg,
  OffsetDateTime scheduledStart,String priority,String shiftCode,String operatorName,Integer setupMinutes,
  Integer cleaningMinutes,String notes,Integer versionNo,List<OperationRequest> operations){}
 public record StatusRequest(String status,String comment){}
 public record CancellationRequestInput(String reason){}
 public record CancellationDecisionInput(boolean approve,String comment){}
 public record MaterialLineRequest(UUID id,String stockStatus,BigDecimal availableQuantityKg,String comment){}
 public record MaterialCheckRequest(List<MaterialLineRequest> materials,String comment){}
 public record OperationStatusRequest(String status,String operatorName,String comment){}
 public record MaterialActualRequest(BigDecimal actualQuantityKg,String comment){}
 public record ExecutionSummaryRequest(BigDecimal actualOutputWeightKg,BigDecimal reprocessWeightKg,BigDecimal wasteWeightKg,BigDecimal waterConsumptionL,BigDecimal energyKwh,String executionNotes){}
 public record IncidentRequest(String incidentType,String severity,Integer downtimeMinutes,String description,String correctiveAction){}
 public record RollDto(ProductionOrderRoll assignment,String rollCode,String receptionNumber){}
 public record ProductionDto(ProductionOrder header,String clientName,String orderNumber,int orderLine,String labNumber,
  String formulaCode,int recipeVersion,String alternative,String machineCode,String machineName,String fabricName,
  String colorName,String processName,String finishName,List<RollDto> rolls,
  List<ProductionMaterialRequirement> materials,List<ProductionOperation> operations,List<ProductionEvent> events,Map<String,Object> execution,ProductionCancellationRequest cancellationRequest){}

 @GetMapping
 @PreAuthorize("hasAuthority('PRODUCTION_ORDERS_READ')")
 public List<ProductionDto> list(){
  return productionOrders.findAll().stream().sorted(Comparator.comparing(ProductionOrder::getScheduledStart).reversed()).map(this::dto).toList();
 }

 @GetMapping("/{id}")
 @PreAuthorize("hasAuthority('PRODUCTION_ORDERS_READ')")
 public ProductionDto one(@PathVariable UUID id){return dto(find(id));}

 @GetMapping("/eligible-batches")
 @PreAuthorize("hasAuthority('PRODUCTION_ORDERS_READ')")
 public List<EligibleBatch> eligible(){
  List<EligibleBatch> result=new ArrayList<>();
  for(ColorLabRequest lab:labRequests.findAll()){
   if(!"APPROVED".equals(lab.getStatus())||lab.getReleasedRecipeId()==null)continue;
   LabRecipeVersion recipe=labRecipes.findById(lab.getReleasedRecipeId()).orElse(null);
   if(recipe==null||!"RELEASED".equals(recipe.getStatus())||recipe.getFormulaId()==null||recipe.getFormulaVersionId()==null)continue;
   SalesOrder order=salesOrders.findById(lab.getSalesOrderId()).orElse(null);SalesOrderItem item=salesItems.findById(lab.getSalesOrderItemId()).orElse(null);
   if(order==null||item==null)continue;
   List<RollOption> available=new ArrayList<>();
   for(ReceptionRoll roll:receptionRolls.findBySalesOrderItemIdOrderByCreatedAt(item.getId())){
    FabricReception reception=receptions.findById(roll.getReceptionId()).orElse(null);
    if(reception==null||!"RELEASED".equals(reception.getStatus())||rollUsed(roll.getId(),null))continue;
    available.add(new RollOption(roll.getId(),roll.getRollCode(),reception.getReceptionNumber(),zero(roll.getNetKg()),zero(roll.getMeters())));
   }
   if(available.isEmpty())continue;
   BigDecimal weight=available.stream().map(RollOption::netKg).reduce(BigDecimal.ZERO,BigDecimal::add);
   Formula formula=formulas.findById(recipe.getFormulaId()).orElse(null);
   result.add(new EligibleBatch(lab.getId(),lab.getLabNumber(),recipe.getId(),recipe.getVersionNo(),recipe.getAlternativeCode(),
    recipe.getFormulaId(),formula==null?"Fórmula no disponible":formula.getCode(),order.getId(),order.getOrderNumber(),item.getId(),item.getLineNo(),
    order.getClientId(),clientName(order.getClientId()),item.getFabricId(),fabricName(item.getFabricId()),item.getColorId(),colorName(item.getColorId()),colorFamily(item.getColorId()),
    item.getProcessId(),processName(item.getProcessId()),item.getFinishId(),finishName(item.getFinishId()),recipe.getBathRatio(),recipe.getTimeMinutes(),weight,available));
  }
  result.sort(Comparator.comparing(EligibleBatch::orderNumber).thenComparingInt(EligibleBatch::orderLine));
  return result;
 }

 @PostMapping
 @Transactional
 @PreAuthorize("hasAuthority('PRODUCTION_PLANNING')")
 public ProductionDto create(@RequestBody PlanRequest input,Authentication auth){
  ColorLabRequest lab=validateLab(input.labRequestId());LabRecipeVersion recipe=labRecipes.findById(lab.getReleasedRecipeId()).orElseThrow();
  long sequence=productionOrders.nextNumber();
  ProductionOrder order=new ProductionOrder();order.setProductionNumber("OP-"+String.format("%06d",sequence));order.setBatchNumber("LOT-"+String.format("%06d",sequence));
  applyPlan(order,lab,recipe,input,null,auth.getName());order=productionOrders.save(order);
  saveAssignments(order,lab,recipe,input,auth.getName());
  addEvent(order,"CREATED",null,"PLANNED","Orden programada",auth.getName());
  audit.record("OP_PROGRAMADA","Producción",order.getId().toString(),order.getProductionNumber()+" · "+order.getBatchNumber());
  return dto(order);
 }

 @PutMapping("/{id}")
 @Transactional
 @PreAuthorize("hasAuthority('PRODUCTION_PLANNING')")
 public ProductionDto update(@PathVariable UUID id,@RequestBody PlanRequest input,Authentication auth){
  ProductionOrder order=findLocked(id);if(!"PLANNED".equals(order.getStatus()))throw new IllegalArgumentException("Solo una orden planificada puede reprogramarse");
   if(pendingCancellation(id))throw new IllegalArgumentException("No se puede reprogramar: existe una solicitud de cancelacion pendiente");
  if(input.versionNo()==null||input.versionNo()!=order.getVersionNo())throw new IllegalArgumentException("La orden fue modificada por otro usuario; actualice la pantalla");
  if(input.labRequestId()!=null&&!input.labRequestId().equals(order.getLabRequestId()))throw new IllegalArgumentException("La receta de origen no puede cambiarse");
  ColorLabRequest lab=validateLab(order.getLabRequestId());LabRecipeVersion recipe=labRecipes.findById(order.getLabRecipeVersionId()).orElseThrow();
  inventory.releaseReservationsForOrder(id,auth.getName(),"OP reprogramada; reserva anterior liberada");
  if(order.getSourceReturnId()!=null){
   applyReturnReplan(order,lab,input,id);order.setVersionNo(order.getVersionNo()+1);order=productionOrders.save(order);
   for(ProductionMaterialRequirement material:materials.findByProductionOrderIdOrderByLineNo(id)){
    material.setAvailableQuantityKg(null);material.setShortageQuantityKg(BigDecimal.ZERO);material.setStockStatus("PENDING");
    material.setWarehouseComment("Reproceso reprogramado; vuelva a reservar stock");material.setCheckedBy(null);material.setCheckedAt(null);materials.save(material);
   }
  }else{
   applyPlan(order,lab,recipe,input,id,auth.getName());order.setVersionNo(order.getVersionNo()+1);order=productionOrders.save(order);
   productionRolls.deleteByProductionOrderId(id);materials.deleteByProductionOrderId(id);operations.deleteByProductionOrderId(id);
   productionRolls.flush();materials.flush();operations.flush();
   saveAssignments(order,lab,recipe,input,auth.getName());
  }
  addEvent(order,"RESCHEDULED","PLANNED","PLANNED","Máquina, lote u horario actualizados",auth.getName());
  audit.record("OP_REPROGRAMADA","Producción",id.toString(),order.getProductionNumber()+" · versión "+order.getVersionNo());
  return dto(order);
 }

 /** Planificacion solicita una cancelacion, pero la OP no cambia de estado. */
 @PostMapping("/{id}/cancellation-requests")
 @Transactional
 @PreAuthorize("hasAuthority('PRODUCTION_PLANNING') or hasAuthority('ROLE_ADMIN')")
 public ProductionDto requestCancellation(@PathVariable UUID id,@RequestBody CancellationRequestInput input,Authentication auth){
  ProductionOrder order=findLocked(id);
  if(!"PLANNED".equals(order.getStatus()))throw new IllegalArgumentException("Solo se puede solicitar cancelar una OP planificada");
  if(pendingCancellation(id))throw new IllegalArgumentException("Ya existe una solicitud pendiente para esta OP");
  String reason=blank(input==null?null:input.reason());
  if(reason==null||reason.length()<10||reason.length()>500)throw new IllegalArgumentException("Indique un motivo de 10 a 500 caracteres");
  ProductionCancellationRequest request=new ProductionCancellationRequest();
  request.setProductionOrderId(id);request.setReason(reason);request.setRequestedBy(auth.getName());request.setRequestedOrderVersion(order.getVersionNo());
  cancellationRequests.save(request);
  addEvent(order,"CANCEL_REQUESTED","PLANNED","PLANNED",shortComment("Cancelacion solicitada: "+reason),auth.getName());
  audit.record("OP_CANCEL_SOLICITADA","Produccion",id.toString(),order.getProductionNumber()+" · motivo: "+reason);
  return dto(order);
 }

 /** Solo Administrador puede aprobar o rechazar una solicitud vigente. */
 @PostMapping("/{id}/cancellation-requests/{requestId}/decision")
 @Transactional
 @PreAuthorize("hasAuthority('ROLE_ADMIN')")
 public ProductionDto decideCancellation(@PathVariable UUID id,@PathVariable UUID requestId,@RequestBody CancellationDecisionInput input,Authentication auth){
  ProductionOrder order=findLocked(id);
  ProductionCancellationRequest request=cancellationRequests.findById(requestId).orElseThrow(()->new IllegalArgumentException("Solicitud no encontrada"));
  if(!id.equals(request.getProductionOrderId()))throw new IllegalArgumentException("La solicitud no corresponde a esta OP");
  if(!"PENDING".equals(request.getStatus()))throw new IllegalArgumentException("La solicitud ya fue resuelta");
  if(auth.getName().equals(request.getRequestedBy()))throw new org.springframework.security.access.AccessDeniedException("Quien solicita no puede autorizar su propia cancelacion");
  String comment=blank(input==null?null:input.comment());
  if(comment!=null&&comment.length()>500)throw new IllegalArgumentException("El comentario supera 500 caracteres");
  boolean approve=input!=null&&input.approve();
  if(!approve&&(comment==null||comment.length()<5))throw new IllegalArgumentException("Indique motivo del rechazo (minimo 5 caracteres)");
  if(approve){
   if(!"PLANNED".equals(order.getStatus())||order.getVersionNo()!=request.getRequestedOrderVersion())
    throw new IllegalArgumentException("La OP cambio desde la solicitud. Rechace la solicitud y vuelva a evaluar.");
   inventory.releaseReservationsForOrder(id,auth.getName(),"OP cancelada con aprobacion administrativa");
   order.setStatus("CANCELLED");order.setVersionNo(order.getVersionNo()+1);order=productionOrders.save(order);
  }
  request.setStatus(approve?"APPROVED":"REJECTED");request.setDecidedBy(auth.getName());request.setDecidedAt(OffsetDateTime.now());request.setDecisionComment(comment);
  cancellationRequests.save(request);
  addEvent(order,approve?"CANCEL_APPROVED":"CANCEL_REJECTED","PLANNED",approve?"CANCELLED":"PLANNED",
   shortComment(approve?"Cancelacion aprobada por Administrador":"Solicitud de cancelacion rechazada: "+comment),auth.getName());
  audit.record(approve?"OP_CANCEL_APROBADA":"OP_CANCEL_RECHAZADA","Produccion",id.toString(),order.getProductionNumber()+" · solicitud "+requestId);
  return dto(order);
 }

 private boolean pendingCancellation(UUID orderId){return cancellationRequests.findByProductionOrderIdAndStatus(orderId,"PENDING").isPresent();}
 private String shortComment(String value){return value.length()<=500?value:value.substring(0,500);}

 @PostMapping("/{id}/materials")
 @Transactional
 @PreAuthorize("hasAuthority('PRODUCTION_MATERIALS_CHECK')")
 public ProductionDto checkMaterials(@PathVariable UUID id,@RequestBody MaterialCheckRequest input,Authentication auth){
  throw new IllegalArgumentException("Desde v0.9.8 la disponibilidad no se registra manualmente. Use Almacén / Kardex → Reservar stock para calcular existencias reales.");
 }

 @PostMapping("/{id}/status")
 @Transactional
 @PreAuthorize("hasAuthority('PRODUCTION_PLANNING') or hasAuthority('PRODUCTION_EXECUTE')")
 public ProductionDto changeStatus(@PathVariable UUID id,@RequestBody StatusRequest input,Authentication auth){
  ProductionOrder order=findLocked(id);String from=order.getStatus(),target=normal(input.status());
   if("CANCELLED".equals(target))throw new org.springframework.security.access.AccessDeniedException("La cancelacion requiere solicitud y aprobacion del Administrador");
   if("PLANNED".equals(from)&&pendingCancellation(id))throw new IllegalArgumentException("Resuelva la solicitud de cancelacion antes de iniciar la produccion");
  Map<String,Set<String>> allowed=Map.of("PLANNED",Set.of("IN_PROCESS"),"IN_PROCESS",Set.of("PAUSED","IN_QUALITY"),"PAUSED",Set.of("IN_PROCESS"),"IN_QUALITY",Set.of(),"REPROCESS_REQUIRED",Set.of("IN_PROCESS"),"READY_FOR_DISPATCH",Set.of(),"QUALITY_REJECTED",Set.of(),"CANCELLED",Set.of());
  if(!allowed.getOrDefault(from,Set.of()).contains(target))throw new IllegalArgumentException("Cambio de estado no permitido: "+from+" → "+target);
  require(auth,"PRODUCTION_EXECUTE");
  if("IN_PROCESS".equals(target)&&"PLANNED".equals(from)){
   maintenance.assertMachineAvailableNow(order.getMachineId());
   if(!"RESERVED".equals(order.getMaterialStatus()))throw new IllegalArgumentException("Almacén debe reservar el 100% de los insumos con stock real antes de iniciar producción");
   order.setActualStart(OffsetDateTime.now());order.setPauseReason(null);
  }
  if("PAUSED".equals(target)){if(blank(input.comment())==null)throw new IllegalArgumentException("Indique el motivo de la pausa");order.setPauseReason(blank(input.comment()));}
  if("IN_PROCESS".equals(target)&&"PAUSED".equals(from))order.setPauseReason(null);
  if("IN_PROCESS".equals(target)&&"REPROCESS_REQUIRED".equals(from)){
   maintenance.assertMachineAvailableNow(order.getMachineId());
   for(ProductionOrder existing:productionOrders.findByMachineIdOrderByScheduledStart(order.getMachineId())){
    if(!existing.getId().equals(order.getId())&&Set.of("IN_PROCESS","PAUSED").contains(existing.getStatus()))
     throw new IllegalArgumentException("La máquina está ocupada actualmente por "+existing.getProductionNumber()+"; reprograme el reproceso o espere a que quede disponible");
   }
   order.setPauseReason(null);order.setActualEnd(null);
  }
  if("IN_QUALITY".equals(target)){
   if(operations.findByProductionOrderIdOrderBySequenceNo(id).stream().anyMatch(x->!Set.of("COMPLETED","SKIPPED").contains(x.getStatus())))
    throw new IllegalArgumentException("Complete o justifique todas las operaciones antes de enviar a calidad");
   execution.validateReadyForQuality(id);
   inventory.releaseReservationsForOrder(id,auth.getName(),"Producción terminada; reservas no consumidas liberadas");
   order.setActualEnd(OffsetDateTime.now());
   maintenance.postProductionMeterHours(order.getId(),order.getMachineId());
  }
  order.setStatus(target);order.setVersionNo(order.getVersionNo()+1);order=productionOrders.save(order);
  addEvent(order,"STATUS",from,target,blank(input.comment()),auth.getName());
  audit.record("OP_ESTADO","Producción",id.toString(),order.getProductionNumber()+" · "+from+" → "+target);
  return dto(order);
 }

 @PostMapping("/{id}/operations/{operationId}/status")
 @Transactional
 @PreAuthorize("hasAuthority('PRODUCTION_EXECUTE')")
 public ProductionDto operationStatus(@PathVariable UUID id,@PathVariable UUID operationId,@RequestBody OperationStatusRequest input,Authentication auth){
  ProductionOrder order=findLocked(id);if(!"IN_PROCESS".equals(order.getStatus()))throw new IllegalArgumentException("La orden debe estar en proceso");
  ProductionOperation operation=operations.findById(operationId).orElseThrow();
  if(!operation.getProductionOrderId().equals(id))throw new IllegalArgumentException("La operación no pertenece a esta orden");
  String from=operation.getStatus(),target=normal(input.status());
  Map<String,Set<String>> allowed=Map.of("PENDING",Set.of("IN_PROCESS","SKIPPED"),"IN_PROCESS",Set.of("COMPLETED"),"COMPLETED",Set.of(),"SKIPPED",Set.of());
  if(!allowed.getOrDefault(from,Set.of()).contains(target))throw new IllegalArgumentException("Cambio de operación no permitido");
  List<ProductionOperation> route=operations.findByProductionOrderIdOrderBySequenceNo(id);
  if("IN_PROCESS".equals(target)){
   if(route.stream().anyMatch(x->"IN_PROCESS".equals(x.getStatus())))throw new IllegalArgumentException("Finalice la operación en curso antes de iniciar otra");
   if(route.stream().anyMatch(x->x.getSequenceNo()<operation.getSequenceNo()&&!Set.of("COMPLETED","SKIPPED").contains(x.getStatus())))
    throw new IllegalArgumentException("Complete las operaciones anteriores");
   operation.setActualStart(OffsetDateTime.now());
  }
  if("SKIPPED".equals(target)&&blank(input.comment())==null)throw new IllegalArgumentException("Indique por qué se omite la operación");
  if("COMPLETED".equals(target))operation.setActualEnd(OffsetDateTime.now());
  operation.setStatus(target);operation.setOperatorName(blank(input.operatorName())==null?order.getOperatorName():blank(input.operatorName()));operation.setNotes(blank(input.comment()));operations.save(operation);
  order.setVersionNo(order.getVersionNo()+1);order=productionOrders.save(order);
  addEvent(order,"OPERATION",order.getStatus(),order.getStatus(),operation.getSequenceNo()+" · "+operation.getOperationName()+" → "+target,auth.getName());
  audit.record("OP_OPERACION","Producción",id.toString(),order.getProductionNumber()+" · "+operation.getOperationName()+" → "+target);
  return dto(order);
 }

 @PostMapping("/{id}/materials/{requirementId}/actual")
 @Transactional
 @PreAuthorize("hasAuthority('PRODUCTION_ACTUALS') or hasAuthority('PRODUCTION_EXECUTE')")
 public ProductionDto materialActual(@PathVariable UUID id,@PathVariable UUID requirementId,@RequestBody MaterialActualRequest input,Authentication auth){
  execution.setMaterialActual(id,requirementId,new ProductionExecutionService.MaterialActualRequest(input.actualQuantityKg(),input.comment()),auth.getName());
  return dto(find(id));
 }

 @PostMapping("/{id}/execution-summary")
 @Transactional
 @PreAuthorize("hasAuthority('PRODUCTION_ACTUALS') or hasAuthority('PRODUCTION_EXECUTE')")
 public ProductionDto executionSummary(@PathVariable UUID id,@RequestBody ExecutionSummaryRequest input,Authentication auth){
  execution.saveSummary(id,new ProductionExecutionService.ExecutionSummaryRequest(input.actualOutputWeightKg(),input.reprocessWeightKg(),input.wasteWeightKg(),input.waterConsumptionL(),input.energyKwh(),input.executionNotes()),auth.getName());
  return dto(find(id));
 }

 @PostMapping("/{id}/incidents")
 @Transactional
 @PreAuthorize("hasAuthority('PRODUCTION_ACTUALS') or hasAuthority('PRODUCTION_EXECUTE')")
 public ProductionDto incident(@PathVariable UUID id,@RequestBody IncidentRequest input,Authentication auth){
  execution.addIncident(id,new ProductionExecutionService.IncidentRequest(input.incidentType(),input.severity(),input.downtimeMinutes(),input.description(),input.correctiveAction()),auth.getName());
  return dto(find(id));
 }

 private void applyPlan(ProductionOrder order,ColorLabRequest lab,LabRecipeVersion recipe,PlanRequest input,UUID excludeOrderId,String actor){
  Machine machine=machines.findLockedById(required(input.machineId(),"Seleccione una máquina")).orElseThrow();
  if(!machine.isActive()||(!"AVAILABLE".equalsIgnoreCase(machine.getOperationalStatus())&&machine.getOperationalStatus()!=null))
   throw new IllegalArgumentException("La máquina no está disponible para programación");
  String process=processName(lab.getProcessId());String compatible=machine.getCompatibleProcesses();
  if(compatible!=null&&!compatible.isBlank()&&!compatible.toLowerCase(Locale.ROOT).contains(process.toLowerCase(Locale.ROOT)))
   throw new IllegalArgumentException("La máquina no está configurada para el proceso "+process);
  List<ReceptionRoll> selected=validateRolls(lab,input.rollIds(),excludeOrderId);
  BigDecimal derived=selected.stream().map(ReceptionRoll::getNetKg).map(this::zero).reduce(BigDecimal.ZERO,BigDecimal::add);
  BigDecimal weight=derived.compareTo(BigDecimal.ZERO)>0?derived:input.manualBatchWeightKg();
  planning.validateCapacity(weight,machine.getMinCapacityKg(),machine.getMaxCapacityKg());
  List<OperationRequest> route=route(input.operations(),process,finishName(lab.getFinishId()),lab.getFinishId()!=null,recipe.getTimeMinutes());
  int operationMinutes=route.stream().map(OperationRequest::plannedMinutes).reduce(0,Integer::sum);
  int setup=input.setupMinutes()==null?0:input.setupMinutes(),cleaning=input.cleaningMinutes()==null?0:input.cleaningMinutes();
  OffsetDateTime end=planning.scheduledEnd(input.scheduledStart(),setup,operationMinutes,cleaning);
  validateMachineConflict(machine.getId(),input.scheduledStart(),end,excludeOrderId);
  maintenance.assertNoMaintenanceConflict(machine.getId(),input.scheduledStart(),end);
  order.setLabRequestId(lab.getId());order.setLabRecipeVersionId(recipe.getId());order.setFormulaId(recipe.getFormulaId());order.setFormulaVersionId(recipe.getFormulaVersionId());
  order.setSalesOrderId(lab.getSalesOrderId());order.setSalesOrderItemId(lab.getSalesOrderItemId());order.setClientId(lab.getClientId());order.setMachineId(machine.getId());
  order.setPriority(enumValue(input.priority(),Set.of("NORMAL","HIGH","URGENT"),"Prioridad no válida","NORMAL"));
  order.setShiftCode(enumValue(input.shiftCode(),Set.of("MORNING","AFTERNOON","NIGHT","CUSTOM"),"Turno no válido","MORNING"));
  order.setOperatorName(blank(input.operatorName()));order.setColorFamily(colorFamily(lab.getColorId()));order.setBatchWeightKg(weight.setScale(3,RoundingMode.HALF_UP));
  order.setBathVolumeL(planning.bathVolume(weight,recipe.getBathRatio()));order.setScheduledStart(input.scheduledStart());order.setScheduledEnd(end);
  order.setEstimatedMinutes(operationMinutes);order.setSetupMinutes(setup);order.setCleaningMinutes(cleaning);order.setNotes(blank(input.notes()));order.setMaterialStatus("PENDING");order.setCreatedBy(order.getCreatedBy()==null?actor:order.getCreatedBy());
 }

 private void applyReturnReplan(ProductionOrder order,ColorLabRequest lab,PlanRequest input,UUID excludeOrderId){
  Machine machine=machines.findLockedById(required(input.machineId(),"Seleccione una máquina")).orElseThrow();
  if(!machine.isActive()||(!"AVAILABLE".equalsIgnoreCase(machine.getOperationalStatus())&&machine.getOperationalStatus()!=null))
   throw new IllegalArgumentException("La máquina no está disponible para programación");
  String process=processName(lab.getProcessId());String compatible=machine.getCompatibleProcesses();
  if(compatible!=null&&!compatible.isBlank()&&!compatible.toLowerCase(Locale.ROOT).contains(process.toLowerCase(Locale.ROOT)))
   throw new IllegalArgumentException("La máquina no está configurada para el proceso "+process);
  planning.validateCapacity(order.getBatchWeightKg(),machine.getMinCapacityKg(),machine.getMaxCapacityKg());
  int operationMinutes=operations.findByProductionOrderIdOrderBySequenceNo(order.getId()).stream().mapToInt(ProductionOperation::getPlannedMinutes).sum();
  if(operationMinutes<=0)operationMinutes=Math.max(1,order.getEstimatedMinutes());
  int setup=input.setupMinutes()==null?order.getSetupMinutes():Math.max(0,input.setupMinutes());
  int cleaning=input.cleaningMinutes()==null?order.getCleaningMinutes():Math.max(0,input.cleaningMinutes());
  OffsetDateTime start=required(input.scheduledStart(),"Indique fecha y hora programada");
  OffsetDateTime end=planning.scheduledEnd(start,setup,operationMinutes,cleaning);
  validateMachineConflict(machine.getId(),start,end,excludeOrderId);maintenance.assertNoMaintenanceConflict(machine.getId(),start,end);
  order.setMachineId(machine.getId());order.setPriority(enumValue(input.priority(),Set.of("NORMAL","HIGH","URGENT"),"Prioridad no válida",order.getPriority()));
  order.setShiftCode(enumValue(input.shiftCode(),Set.of("MORNING","AFTERNOON","NIGHT","CUSTOM"),"Turno no válido",order.getShiftCode()));
  if(input.operatorName()!=null)order.setOperatorName(blank(input.operatorName()));
  order.setScheduledStart(start);order.setScheduledEnd(end);order.setEstimatedMinutes(operationMinutes);order.setSetupMinutes(setup);order.setCleaningMinutes(cleaning);
  if(input.notes()!=null)order.setNotes(blank(input.notes()));order.setMaterialStatus("PENDING");
 }

 private void saveAssignments(ProductionOrder order,ColorLabRequest lab,LabRecipeVersion recipe,PlanRequest input,String actor){
  List<ReceptionRoll> selected=validateRolls(lab,input.rollIds(),order.getId());int sequence=1;
  for(ReceptionRoll source:selected){ProductionOrderRoll row=new ProductionOrderRoll();row.setProductionOrderId(order.getId());row.setReceptionRollId(source.getId());row.setSequenceNo(sequence++);row.setNetKg(zero(source.getNetKg()));row.setMeters(zero(source.getMeters()));productionRolls.save(row);}
  int line=1;for(LabRecipeComponent source:labComponents.findByRecipeVersionIdOrderByLineNo(recipe.getId())){
   ProductionMaterialRequirement row=new ProductionMaterialRequirement();row.setProductionOrderId(order.getId());row.setLineNo(line++);row.setMaterialCode(source.getMaterialCode());row.setMaterialName(source.getMaterialName());
   row.setDosage(source.getDosage());row.setDosageUnit(source.getDosageUnit());row.setPlannedQuantityKg(planning.materialQuantity(order.getBatchWeightKg(),order.getBathVolumeL(),source.getDosage(),source.getDosageUnit()));materials.save(row);
  }
  List<OperationRequest> route=route(input.operations(),processName(lab.getProcessId()),finishName(lab.getFinishId()),lab.getFinishId()!=null,recipe.getTimeMinutes());sequence=1;
  for(OperationRequest source:route){ProductionOperation row=new ProductionOperation();row.setProductionOrderId(order.getId());row.setSequenceNo(sequence++);row.setOperationCode(source.operationCode());row.setOperationName(source.operationName());row.setPlannedMinutes(source.plannedMinutes());operations.save(row);}
 }

 private List<ReceptionRoll> validateRolls(ColorLabRequest lab,List<UUID> ids,UUID excludeOrderId){
  if(ids==null||ids.isEmpty())throw new IllegalArgumentException("Seleccione al menos un rollo para el lote");
  if(new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("Hay rollos duplicados en el lote");
  Map<UUID,ReceptionRoll> locked=new HashMap<>();
  ids.stream().sorted().forEach(id->locked.put(id,receptionRolls.findLockedById(id).orElseThrow()));
  List<ReceptionRoll> selected=new ArrayList<>();
  for(UUID id:ids){
   ReceptionRoll roll=locked.get(id);if(!roll.getSalesOrderItemId().equals(lab.getSalesOrderItemId()))throw new IllegalArgumentException("Un rollo no pertenece a la partida aprobada");
   FabricReception reception=receptions.findById(roll.getReceptionId()).orElseThrow();if(!"RELEASED".equals(reception.getStatus()))throw new IllegalArgumentException("Todos los rollos deben provenir de recepciones liberadas");
   if(rollUsed(id,excludeOrderId))throw new IllegalArgumentException("El rollo "+roll.getRollCode()+" ya está asignado a otra OP");
   selected.add(roll);
  }
  return selected;
 }
 private boolean rollUsed(UUID rollId,UUID excludeOrderId){
  for(ProductionOrderRoll assignment:productionRolls.findByReceptionRollId(rollId)){
   if(excludeOrderId!=null&&excludeOrderId.equals(assignment.getProductionOrderId()))continue;
   ProductionOrder existing=productionOrders.findById(assignment.getProductionOrderId()).orElse(null);
   if(existing!=null&&!"CANCELLED".equals(existing.getStatus()))return true;
  }
  return false;
 }
 private void validateMachineConflict(UUID machineId,OffsetDateTime start,OffsetDateTime end,UUID excludeOrderId){
  for(ProductionOrder existing:productionOrders.findByMachineIdOrderByScheduledStart(machineId)){
   if(excludeOrderId!=null&&excludeOrderId.equals(existing.getId()))continue;
   if(BUSY_MACHINE_STATES.contains(existing.getStatus())&&planning.overlaps(start,end,existing.getScheduledStart(),existing.getScheduledEnd()))
    throw new IllegalArgumentException("La máquina ya está ocupada por "+existing.getProductionNumber()+" entre "+existing.getScheduledStart()+" y "+existing.getScheduledEnd());
  }
 }
 private List<OperationRequest> route(List<OperationRequest> input,String process,String finish,boolean hasFinish,Integer recipeMinutes){
  List<OperationRequest> values=input;
  if(values==null||values.isEmpty()){values=new ArrayList<>();values.add(new OperationRequest("PREP","Preparación y carga",30));values.add(new OperationRequest("DYE",process,recipeMinutes==null||recipeMinutes<=0?60:recipeMinutes));values.add(new OperationRequest("WASH","Lavado y neutralizado",30));if(hasFinish)values.add(new OperationRequest("FINISH",finish,45));values.add(new OperationRequest("UNLOAD_QC","Descarga y envío a calidad",20));}
  if(values.size()>20)throw new IllegalArgumentException("La ruta no puede superar 20 operaciones");
  List<OperationRequest> result=new ArrayList<>();int line=1;
  for(OperationRequest value:values){if(blank(value.operationName())==null||value.plannedMinutes()==null||value.plannedMinutes()<=0)throw new IllegalArgumentException("Complete nombre y minutos de cada operación");String code=blank(value.operationCode())==null?"STEP-"+line:normal(value.operationCode());result.add(new OperationRequest(code,value.operationName().trim(),value.plannedMinutes()));line++;}
  return result;
 }

 private ProductionDto dto(ProductionOrder order){
  ColorLabRequest lab=labRequests.findById(order.getLabRequestId()).orElseThrow();LabRecipeVersion recipe=labRecipes.findById(order.getLabRecipeVersionId()).orElseThrow();
  SalesOrder sales=salesOrders.findById(order.getSalesOrderId()).orElseThrow();SalesOrderItem item=salesItems.findById(order.getSalesOrderItemId()).orElseThrow();Machine machine=machines.findById(order.getMachineId()).orElseThrow();
  Formula formula=formulas.findById(order.getFormulaId()).orElseThrow();List<RollDto> assigned=new ArrayList<>();
  for(ProductionOrderRoll row:productionRolls.findByProductionOrderIdOrderBySequenceNo(order.getId())){ReceptionRoll roll=receptionRolls.findById(row.getReceptionRollId()).orElseThrow();FabricReception reception=receptions.findById(roll.getReceptionId()).orElseThrow();assigned.add(new RollDto(row,roll.getRollCode(),reception.getReceptionNumber()));}
  return new ProductionDto(order,clientName(order.getClientId()),sales.getOrderNumber(),item.getLineNo(),lab.getLabNumber(),formula.getCode(),recipe.getVersionNo(),recipe.getAlternativeCode(),machine.getCode(),machine.getName(),
   fabricName(item.getFabricId()),colorName(item.getColorId()),processName(item.getProcessId()),finishName(item.getFinishId()),assigned,
   materials.findByProductionOrderIdOrderByLineNo(order.getId()),operations.findByProductionOrderIdOrderBySequenceNo(order.getId()),events.findByProductionOrderIdOrderByCreatedAt(order.getId()),execution.snapshot(order.getId()),cancellationRequests.findFirstByProductionOrderIdOrderByRequestedAtDesc(order.getId()).orElse(null));
 }
 private ColorLabRequest validateLab(UUID id){ColorLabRequest lab=labRequests.findById(required(id,"Seleccione una receta liberada")).orElseThrow();if(!"APPROVED".equals(lab.getStatus())||lab.getReleasedRecipeId()==null)throw new IllegalArgumentException("La partida necesita una receta aprobada y liberada");return lab;}
 private ProductionOrder find(UUID id){return productionOrders.findById(id).orElseThrow();} private ProductionOrder findLocked(UUID id){return productionOrders.findLockedById(id).orElseThrow();}
 private void addEvent(ProductionOrder order,String type,String from,String to,String comment,String actor){ProductionEvent e=new ProductionEvent();e.setProductionOrderId(order.getId());e.setEventType(type);e.setFromStatus(from);e.setToStatus(to);e.setComment(comment);e.setActor(actor);events.save(e);}
 private void require(Authentication auth,String authority){if(auth.getAuthorities().stream().noneMatch(a->authority.equals(a.getAuthority())||"ROLE_ADMIN".equals(a.getAuthority())))throw new org.springframework.security.access.AccessDeniedException("No tiene permiso para esta acción");}
 private String clientName(UUID id){return clients.findById(id).map(Client::getName).orElse("Cliente no disponible");}
 private String fabricName(UUID id){return fabrics.findById(id).map(Fabric::getName).orElse("Tela no disponible");}
 private String colorName(UUID id){return colors.findById(id).map(DyeColor::getName).orElse("Color no disponible");}
 private String colorFamily(UUID id){return colors.findById(id).map(DyeColor::getFamily).orElse("");}
 private String processName(UUID id){return processes.findById(id).map(ProcessType::getName).orElse("Proceso no disponible");}
 private String finishName(UUID id){return id==null?"Sin acabado":finishes.findById(id).map(FinishType::getName).orElse("Acabado no disponible");}
 private String enumValue(String value,Set<String> valid,String message,String fallback){String normalized=normal(value);if(normalized.isBlank())return fallback;if(!valid.contains(normalized))throw new IllegalArgumentException(message);return normalized;}
 private String normal(String value){return value==null?"":value.trim().toUpperCase(Locale.ROOT);} private String blank(String value){return value==null||value.isBlank()?null:value.trim();}
 private BigDecimal zero(BigDecimal value){return value==null?BigDecimal.ZERO:value;} private <T>T required(T value,String message){if(value==null)throw new IllegalArgumentException(message);return value;}
}
