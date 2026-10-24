package com.ngtintoreria.erp.laboratory;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.crm.*;
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

@RestController
@RequestMapping("/api/lab-requests")
public class ColorLaboratoryController {
 private static final Set<String> ACTIVE=Set.of("IN_DEVELOPMENT","SENT","APPROVED","REJECTED");
 private final ColorLabRequestRepository requests; private final LabAlternativeRepository alternatives;
 private final LabRecipeVersionRepository recipes; private final LabRecipeComponentRepository recipeComponents;
 private final LabEventRepository events; private final LabCalculationService calculations;
 private final FabricReceptionRepository receptions; private final ReceptionRollRepository rolls;
 private final SalesOrderRepository orders; private final SalesOrderItemRepository orderItems;
 private final ClientRepository clients; private final FabricRepository fabrics; private final DyeColorRepository colors;
 private final ProcessTypeRepository processes; private final FinishTypeRepository finishes;
 private final FormulaRepository formulas; private final FormulaVersionRepository formulaVersions;
 private final FormulaComponentRepository formulaComponents; private final AuditService audit;

 public ColorLaboratoryController(ColorLabRequestRepository requests,LabAlternativeRepository alternatives,
  LabRecipeVersionRepository recipes,LabRecipeComponentRepository recipeComponents,LabEventRepository events,
  LabCalculationService calculations,FabricReceptionRepository receptions,ReceptionRollRepository rolls,
  SalesOrderRepository orders,SalesOrderItemRepository orderItems,ClientRepository clients,FabricRepository fabrics,
  DyeColorRepository colors,ProcessTypeRepository processes,FinishTypeRepository finishes,FormulaRepository formulas,
  FormulaVersionRepository formulaVersions,FormulaComponentRepository formulaComponents,AuditService audit){
  this.requests=requests;this.alternatives=alternatives;this.recipes=recipes;this.recipeComponents=recipeComponents;
  this.events=events;this.calculations=calculations;this.receptions=receptions;this.rolls=rolls;this.orders=orders;
  this.orderItems=orderItems;this.clients=clients;this.fabrics=fabrics;this.colors=colors;this.processes=processes;
  this.finishes=finishes;this.formulas=formulas;this.formulaVersions=formulaVersions;this.formulaComponents=formulaComponents;this.audit=audit;
 }

 public record EligibleSource(UUID receptionId,String receptionNumber,LocalDate receivedDate,UUID orderId,String orderNumber,
  UUID orderItemId,int lineNo,UUID clientId,String clientName,UUID fabricId,String fabricName,UUID colorId,String colorName,
  UUID processId,String processName,UUID finishId,String finishName,BigDecimal actualWeightKg,BigDecimal meters){}
 public record CreateRequest(UUID sourceReceptionId,UUID salesOrderItemId,String referenceMethod,String colorStandard,
  String illuminant,String substrate,BigDecimal actualWeightKg,String observations){}
 public record AlternativeRequest(String alternativeCode,BigDecimal lValue,BigDecimal aValue,BigDecimal bValue,
  BigDecimal deltaE,String visualResult,String notes){}
 public record UpdateRequest(String referenceMethod,String colorStandard,String illuminant,String substrate,
  String observations,Integer versionNo,List<AlternativeRequest> alternatives){}
 public record RecipeComponentRequest(String materialCode,String materialName,BigDecimal dosage,String dosageUnit,String notes){}
 public record RecipeRequest(String alternativeCode,BigDecimal bathRatio,BigDecimal temperatureC,Integer timeMinutes,
  BigDecimal ph,String curveText,String changeReason,List<RecipeComponentRequest> components){}
 public record StatusRequest(String status,String selectedAlternative,String comment){}
 public record RecipeDto(LabRecipeVersion recipe,List<LabRecipeComponent> components){}
 public record LabDto(ColorLabRequest header,String clientName,String orderNumber,int orderLine,String receptionNumber,
  String fabricName,String colorName,String processName,String finishName,List<LabAlternative> alternatives,
  List<RecipeDto> recipes,List<LabEvent> events){}

 @GetMapping
 @PreAuthorize("hasAuthority('LAB_REQUESTS_READ')")
 public List<LabDto> list(){
  return requests.findAll().stream().sorted(Comparator.comparing(ColorLabRequest::getCreatedAt).reversed()).map(this::dto).toList();
 }

 @GetMapping("/{id}")
 @PreAuthorize("hasAuthority('LAB_REQUESTS_READ')")
 public LabDto one(@PathVariable UUID id){return dto(find(id));}

 @GetMapping("/eligible-sources")
 @PreAuthorize("hasAuthority('LAB_REQUESTS_READ')")
 public List<EligibleSource> eligibleSources(){
  List<EligibleSource> result=new ArrayList<>();
  for(FabricReception reception:receptions.findAll()){
   if(!"RELEASED".equals(reception.getStatus()))continue;
   SalesOrder order=orders.findById(reception.getSalesOrderId()).orElse(null);if(order==null)continue;
   Map<UUID,List<ReceptionRoll>> grouped=new LinkedHashMap<>();
   for(ReceptionRoll roll:rolls.findByReceptionIdOrderByLineNo(reception.getId()))grouped.computeIfAbsent(roll.getSalesOrderItemId(),k->new ArrayList<>()).add(roll);
   for(var entry:grouped.entrySet()){
    SalesOrderItem item=orderItems.findById(entry.getKey()).orElse(null);if(item==null)continue;
    if(requests.existsBySalesOrderItemIdAndStatusIn(item.getId(),ACTIVE))continue;
    BigDecimal kg=entry.getValue().stream().map(ReceptionRoll::getNetKg).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
    BigDecimal meters=entry.getValue().stream().map(ReceptionRoll::getMeters).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
    result.add(new EligibleSource(reception.getId(),reception.getReceptionNumber(),reception.getReceivedDate(),order.getId(),order.getOrderNumber(),
     item.getId(),item.getLineNo(),order.getClientId(),clientName(order.getClientId()),item.getFabricId(),fabricName(item.getFabricId()),
     item.getColorId(),colorName(item.getColorId()),item.getProcessId(),processName(item.getProcessId()),item.getFinishId(),
     finishName(item.getFinishId()),kg,meters));
   }
  }
  result.sort(Comparator.comparing(EligibleSource::receivedDate).reversed().thenComparing(EligibleSource::orderNumber));
  return result;
 }

 @PostMapping
 @Transactional
 @PreAuthorize("hasAuthority('LAB_REQUESTS_WRITE')")
 public LabDto create(@RequestBody CreateRequest input,Authentication auth){
  FabricReception reception=receptions.findById(required(input.sourceReceptionId(),"Seleccione una recepción liberada")).orElseThrow();
  if(!"RELEASED".equals(reception.getStatus()))throw new IllegalArgumentException("Laboratorio solo acepta recepciones liberadas");
  SalesOrder order=orders.findById(reception.getSalesOrderId()).orElseThrow();
  SalesOrderItem item=orderItems.findById(required(input.salesOrderItemId(),"Seleccione una partida del pedido")).orElseThrow();
  if(!item.getSalesOrderId().equals(order.getId()))throw new IllegalArgumentException("La partida no pertenece al pedido de la recepción");
  List<ReceptionRoll> sourceRolls=rolls.findByReceptionIdOrderByLineNo(reception.getId()).stream().filter(r->r.getSalesOrderItemId().equals(item.getId())).toList();
  if(sourceRolls.isEmpty())throw new IllegalArgumentException("La partida seleccionada no tiene rollos en esta recepción");
  if(requests.existsBySalesOrderItemIdAndStatusIn(item.getId(),ACTIVE))throw new IllegalArgumentException("La partida ya tiene una solicitud de laboratorio activa");
  BigDecimal derivedWeight=sourceRolls.stream().map(ReceptionRoll::getNetKg).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
  BigDecimal weight=derivedWeight.compareTo(BigDecimal.ZERO)>0?derivedWeight:input.actualWeightKg();
  if(weight==null||weight.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("Registre el peso real en kg para calcular la receta");
  ColorLabRequest request=new ColorLabRequest();
  request.setLabNumber("LAB-"+String.format("%06d",requests.nextNumber()));request.setSourceReceptionId(reception.getId());
  request.setSalesOrderId(order.getId());request.setSalesOrderItemId(item.getId());request.setClientId(order.getClientId());
  request.setFabricId(item.getFabricId());request.setColorId(item.getColorId());request.setProcessId(item.getProcessId());request.setFinishId(item.getFinishId());
  applyHeader(request,input.referenceMethod(),input.colorStandard(),input.illuminant(),input.substrate(),input.observations());
  request.setActualWeightKg(weight.setScale(3,RoundingMode.HALF_UP));request.setCreatedBy(auth.getName());request=requests.save(request);
  for(String code:List.of("A","B","C")){LabAlternative a=new LabAlternative();a.setLabRequestId(request.getId());a.setAlternativeCode(code);alternatives.save(a);}
  addEvent(request,"CREATED",null,"IN_DEVELOPMENT","Solicitud creada desde "+reception.getReceptionNumber(),auth.getName());
  audit.record("LAB_SOLICITUD_CREADA","Laboratorio",request.getId().toString(),request.getLabNumber()+" · "+order.getOrderNumber()+" partida "+item.getLineNo());
  return dto(request);
 }

 @PutMapping("/{id}")
 @Transactional
 @PreAuthorize("hasAuthority('LAB_REQUESTS_WRITE')")
 public LabDto update(@PathVariable UUID id,@RequestBody UpdateRequest input,Authentication auth){
  ColorLabRequest request=findLocked(id);
  if(!"IN_DEVELOPMENT".equals(request.getStatus()))throw new IllegalArgumentException("Solo una solicitud en desarrollo puede editarse");
  if(input.versionNo()==null||input.versionNo()!=request.getVersionNo())throw new IllegalArgumentException("La solicitud fue modificada por otro usuario; actualice la pantalla");
  applyHeader(request,input.referenceMethod(),input.colorStandard(),input.illuminant(),input.substrate(),input.observations());
  request.setVersionNo(request.getVersionNo()+1);request=requests.save(request);
  if(input.alternatives()!=null)for(AlternativeRequest value:input.alternatives()){
   String code=alternative(value.alternativeCode());LabAlternative entity=alternatives.findByLabRequestIdAndAlternativeCode(id,code).orElseThrow();
   if(value.deltaE()!=null&&value.deltaE().compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException("Delta E no puede ser negativo");
   entity.setLValue(value.lValue());entity.setAValue(value.aValue());entity.setBValue(value.bValue());entity.setDeltaE(value.deltaE());
   String visual=normal(value.visualResult());if(!Set.of("PENDING","MATCH","ACCEPTABLE","OUT_OF_TOLERANCE").contains(visual))throw new IllegalArgumentException("Resultado visual no válido");
   entity.setVisualResult(visual);entity.setNotes(blank(value.notes()));alternatives.save(entity);
  }
  addEvent(request,"UPDATED","IN_DEVELOPMENT","IN_DEVELOPMENT","Datos técnicos actualizados",auth.getName());
  audit.record("LAB_SOLICITUD_ACTUALIZADA","Laboratorio",id.toString(),request.getLabNumber()+" · versión "+request.getVersionNo());
  return dto(request);
 }

 @PostMapping("/{id}/recipes")
 @Transactional
 @PreAuthorize("hasAuthority('LAB_REQUESTS_WRITE')")
 public LabDto createRecipe(@PathVariable UUID id,@RequestBody RecipeRequest input,Authentication auth){
  ColorLabRequest request=findLocked(id);if(!"IN_DEVELOPMENT".equals(request.getStatus()))throw new IllegalArgumentException("La solicitud debe estar en desarrollo");
  String code=alternative(input.alternativeCode());alternatives.findByLabRequestIdAndAlternativeCode(id,code).orElseThrow();
  if(input.components()==null||input.components().isEmpty())throw new IllegalArgumentException("La receta necesita al menos un componente");
  Optional<LabRecipeVersion> previous=recipes.findFirstByLabRequestIdAndAlternativeCodeOrderByVersionNoDesc(id,code);
  int next=previous.map(x->x.getVersionNo()+1).orElse(1);
  if(next>1&&blank(input.changeReason())==null)throw new IllegalArgumentException("Indique el motivo de la nueva versión");
  previous.filter(x->"DRAFT".equals(x.getStatus())).ifPresent(x->{x.setStatus("SUPERSEDED");recipes.save(x);});
  BigDecimal bath=calculations.bathVolume(request.getActualWeightKg(),input.bathRatio());
  LabRecipeVersion recipe=new LabRecipeVersion();recipe.setLabRequestId(id);recipe.setAlternativeCode(code);recipe.setVersionNo(next);
  recipe.setActualWeightKg(request.getActualWeightKg());recipe.setBathRatio(input.bathRatio());recipe.setBathVolumeL(bath);
  recipe.setTemperatureC(input.temperatureC());recipe.setTimeMinutes(input.timeMinutes());recipe.setPh(input.ph());
  recipe.setCurveText(blank(input.curveText()));recipe.setChangeReason(blank(input.changeReason()));recipe.setCreatedBy(auth.getName());recipe=recipes.save(recipe);
  int line=1;for(RecipeComponentRequest value:input.components()){
   if(blank(value.materialName())==null)throw new IllegalArgumentException("Todos los componentes necesitan un nombre");
   String unit=calculations.normalizeUnit(value.dosageUnit());
   LabRecipeComponent component=new LabRecipeComponent();component.setRecipeVersionId(recipe.getId());component.setLineNo(line++);
   component.setMaterialCode(blank(value.materialCode()));component.setMaterialName(value.materialName().trim());component.setDosage(value.dosage());component.setDosageUnit(unit);
   component.setCalculatedQuantityKg(calculations.componentKg(request.getActualWeightKg(),bath,value.dosage(),unit));component.setNotes(blank(value.notes()));recipeComponents.save(component);
  }
  addEvent(request,"RECIPE_VERSION",request.getStatus(),request.getStatus(),"Alternativa "+code+" · V"+String.format("%02d",next),auth.getName());
  audit.record("LAB_RECETA_VERSIONADA","Laboratorio",id.toString(),request.getLabNumber()+" · "+code+" V"+String.format("%02d",next));
  return dto(request);
 }

 @PostMapping("/{id}/status")
 @Transactional
 @PreAuthorize("hasAuthority('LAB_REQUESTS_WRITE') or hasAuthority('LAB_REQUESTS_APPROVE')")
 public LabDto changeStatus(@PathVariable UUID id,@RequestBody StatusRequest input,Authentication auth){
  ColorLabRequest request=findLocked(id),saved;String from=request.getStatus(),target=normal(input.status());
  Map<String,Set<String>> allowed=Map.of("IN_DEVELOPMENT",Set.of("SENT","CANCELLED"),"SENT",Set.of("APPROVED","REJECTED"),
   "REJECTED",Set.of("IN_DEVELOPMENT","CANCELLED"),"APPROVED",Set.of(),"CANCELLED",Set.of());
  if(!allowed.getOrDefault(from,Set.of()).contains(target))throw new IllegalArgumentException("Cambio de estado no permitido: "+from+" → "+target);
  if(Set.of("APPROVED","REJECTED").contains(target))require(auth,"LAB_REQUESTS_APPROVE");else require(auth,"LAB_REQUESTS_WRITE");
  if("SENT".equals(target)&&recipes.findByLabRequestIdOrderByAlternativeCodeAscVersionNoDesc(id).stream().noneMatch(x->"DRAFT".equals(x.getStatus())))
   throw new IllegalArgumentException("Cree al menos una receta antes de enviar las alternativas");
  if("APPROVED".equals(target)){
   String code=alternative(input.selectedAlternative());
   LabRecipeVersion latest=recipes.findFirstByLabRequestIdAndAlternativeCodeOrderByVersionNoDesc(id,code).orElseThrow(()->new IllegalArgumentException("La alternativa aprobada no tiene receta"));
   if(!"DRAFT".equals(latest.getStatus()))throw new IllegalArgumentException("La alternativa aprobada no tiene una receta vigente");
   request.setSelectedAlternative(code);request.setApprovedAt(OffsetDateTime.now());
  }
  if("REJECTED".equals(target)){if(blank(input.comment())==null)throw new IllegalArgumentException("Indique el motivo del rechazo");request.setRejectedAt(OffsetDateTime.now());request.setSelectedAlternative(null);}
  if("SENT".equals(target))request.setSentAt(OffsetDateTime.now());
  if("IN_DEVELOPMENT".equals(target)){request.setRejectedAt(null);request.setSelectedAlternative(null);}
  request.setStatus(target);request.setVersionNo(request.getVersionNo()+1);saved=requests.save(request);
  addEvent(saved,"STATUS",from,target,blank(input.comment()),auth.getName());
  audit.record("LAB_ESTADO","Laboratorio",id.toString(),saved.getLabNumber()+" · "+from+" → "+target);
  return dto(saved);
 }

 @PostMapping("/{id}/release")
 @Transactional
 @PreAuthorize("hasAuthority('LAB_RECIPES_RELEASE')")
 public LabDto release(@PathVariable UUID id,Authentication auth){
  ColorLabRequest request=findLocked(id);
  if(!"APPROVED".equals(request.getStatus())||request.getSelectedAlternative()==null)throw new IllegalArgumentException("Primero registre la alternativa aprobada por el cliente");
  if(request.getReleasedRecipeId()!=null)throw new IllegalArgumentException("La receta ya fue liberada");
  LabRecipeVersion recipe=recipes.findFirstByLabRequestIdAndAlternativeCodeOrderByVersionNoDesc(id,request.getSelectedAlternative()).orElseThrow();
  if(!"DRAFT".equals(recipe.getStatus()))throw new IllegalArgumentException("La receta seleccionada no está vigente");
  String formulaCode=request.getLabNumber()+"-"+request.getSelectedAlternative();
  if(formulas.existsByCode(formulaCode))throw new IllegalArgumentException("Ya existe una fórmula con el código "+formulaCode);
  Formula formula=new Formula();formula.setCode(formulaCode);formula.setName(colorName(request.getColorId())+" · "+clientName(request.getClientId())+" · "+request.getLabNumber());
  formula.setClientId(request.getClientId());formula.setFabricId(request.getFabricId());formula.setColorId(request.getColorId());formula.setProcessId(request.getProcessId());formula.setFinishId(request.getFinishId());formula.setCurrentVersion(1);formula=formulas.save(formula);
  FormulaVersion version=new FormulaVersion();version.setFormulaId(formula.getId());version.setVersionNo(1);version.setStatus("RELEASED");
  version.setBathRatio(recipe.getBathRatio());version.setTemperatureC(recipe.getTemperatureC());version.setTimeMinutes(recipe.getTimeMinutes());
  version.setPh(recipe.getPh());version.setCurveText(recipe.getCurveText());version.setCreatedBy(auth.getName());version.setReleasedAt(OffsetDateTime.now());version=formulaVersions.save(version);
  for(LabRecipeComponent source:recipeComponents.findByRecipeVersionIdOrderByLineNo(recipe.getId())){
   FormulaComponent component=new FormulaComponent();component.setVersionId(version.getId());component.setMaterialCode(source.getMaterialCode());
   component.setMaterialName(source.getMaterialName());component.setDosage(source.getDosage());component.setDosageUnit(source.getDosageUnit());component.setSequenceOrder(source.getLineNo());formulaComponents.save(component);
  }
  recipe.setStatus("RELEASED");recipe.setFormulaId(formula.getId());recipe.setFormulaVersionId(version.getId());recipe.setReleasedBy(auth.getName());recipe.setReleasedAt(OffsetDateTime.now());recipes.save(recipe);
  SalesOrderItem item=orderItems.findById(request.getSalesOrderItemId()).orElseThrow();item.setFormulaId(formula.getId());orderItems.save(item);
  request.setReleasedRecipeId(recipe.getId());request.setVersionNo(request.getVersionNo()+1);request=requests.save(request);
  addEvent(request,"RECIPE_RELEASED","APPROVED","APPROVED","Receta "+formulaCode+" liberada y vinculada al pedido",auth.getName());
  audit.record("LAB_RECETA_LIBERADA","Laboratorio",id.toString(),formulaCode+" · pedido vinculado");
  return dto(request);
 }

 private LabDto dto(ColorLabRequest request){
  SalesOrder order=orders.findById(request.getSalesOrderId()).orElseThrow();SalesOrderItem item=orderItems.findById(request.getSalesOrderItemId()).orElseThrow();
  FabricReception reception=receptions.findById(request.getSourceReceptionId()).orElseThrow();
  List<RecipeDto> recipeDtos=recipes.findByLabRequestIdOrderByAlternativeCodeAscVersionNoDesc(request.getId()).stream()
   .map(r->new RecipeDto(r,recipeComponents.findByRecipeVersionIdOrderByLineNo(r.getId()))).toList();
  return new LabDto(request,clientName(request.getClientId()),order.getOrderNumber(),item.getLineNo(),reception.getReceptionNumber(),
   fabricName(request.getFabricId()),colorName(request.getColorId()),processName(request.getProcessId()),finishName(request.getFinishId()),
   alternatives.findByLabRequestIdOrderByAlternativeCode(request.getId()),recipeDtos,events.findByLabRequestIdOrderByCreatedAt(request.getId()));
 }
 private ColorLabRequest find(UUID id){return requests.findById(id).orElseThrow();}
 private ColorLabRequest findLocked(UUID id){return requests.findLockedById(id).orElseThrow();}
 private void applyHeader(ColorLabRequest request,String method,String standard,String illuminant,String substrate,String observations){
  if(blank(method)==null)throw new IllegalArgumentException("Seleccione el método de referencia");
  if(blank(standard)==null)throw new IllegalArgumentException("El estándar de color es obligatorio");
  if(blank(substrate)==null)throw new IllegalArgumentException("El sustrato es obligatorio");
  request.setReferenceMethod(method.trim());request.setColorStandard(standard.trim());request.setIlluminant(blank(illuminant)==null?"D65":illuminant.trim().toUpperCase(Locale.ROOT));
  request.setSubstrate(substrate.trim());request.setObservations(blank(observations));
 }
 private void addEvent(ColorLabRequest request,String type,String from,String to,String comment,String actor){LabEvent e=new LabEvent();e.setLabRequestId(request.getId());e.setEventType(type);e.setFromStatus(from);e.setToStatus(to);e.setComment(comment);e.setActor(actor);events.save(e);}
 private String clientName(UUID id){return clients.findById(id).map(Client::getName).orElse("Cliente no disponible");}
 private String fabricName(UUID id){return fabrics.findById(id).map(Fabric::getName).orElse("Tela no disponible");}
 private String colorName(UUID id){return colors.findById(id).map(DyeColor::getName).orElse("Color no disponible");}
 private String processName(UUID id){return processes.findById(id).map(ProcessType::getName).orElse("Proceso no disponible");}
 private String finishName(UUID id){return id==null?"Sin acabado":finishes.findById(id).map(FinishType::getName).orElse("Acabado no disponible");}
 private String alternative(String value){String code=normal(value);if(!Set.of("A","B","C").contains(code))throw new IllegalArgumentException("Seleccione la alternativa A, B o C");return code;}
 private String normal(String value){return value==null?"":value.trim().toUpperCase(Locale.ROOT);}
 private String blank(String value){return value==null||value.isBlank()?null:value.trim();}
 private <T> T required(T value,String message){if(value==null)throw new IllegalArgumentException(message);return value;}
 private void require(Authentication auth,String authority){if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals(authority)||a.getAuthority().equals("ROLE_ADMIN")))throw new org.springframework.security.access.AccessDeniedException("No tiene permiso para esta acción");}
}
