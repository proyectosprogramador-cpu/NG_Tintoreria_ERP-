package com.ngtintoreria.erp.masterdata;
import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/masters")
public class MasterDataController {
 private final FabricRepository fabrics; private final DyeColorRepository colors; private final ProcessTypeRepository processes; private final FinishTypeRepository finishes; private final MeasurementUnitRepository units; private final MachineRepository machines; private final AuditService audit;
 public MasterDataController(FabricRepository fabrics,DyeColorRepository colors,ProcessTypeRepository processes,FinishTypeRepository finishes,MeasurementUnitRepository units,MachineRepository machines,AuditService audit){this.fabrics=fabrics;this.colors=colors;this.processes=processes;this.finishes=finishes;this.units=units;this.machines=machines;this.audit=audit;}
 @GetMapping("/{type}") @PreAuthorize("hasAuthority('MASTERS_READ')") public List<?> list(@PathVariable String type){return repo(type).findAll().stream().sorted(Comparator.comparing(x->((AbstractMaster)x).getName(),String.CASE_INSENSITIVE_ORDER)).toList();}
 @PostMapping("/{type}") @PreAuthorize("hasAuthority('MASTERS_WRITE')") public Object create(@PathVariable String type,@RequestBody Map<String,Object> raw){Object entity=convert(type,raw,null);Object out=repo(type).save(entity);audit.record("MAESTRO_CREADO","Maestros",((AbstractMaster)out).getId().toString(),type+": "+((AbstractMaster)out).getName());return out;}
 @PutMapping("/{type}/{id}") @PreAuthorize("hasAuthority('MASTERS_WRITE')") public Object update(@PathVariable String type,@PathVariable UUID id,@RequestBody Map<String,Object> raw){Object existing=repo(type).findById(id).orElseThrow();Object entity=convert(type,raw,(AbstractMaster)existing);Object out=repo(type).save(entity);audit.record("MAESTRO_ACTUALIZADO","Maestros",id.toString(),type+": "+((AbstractMaster)out).getName());return out;}
 @SuppressWarnings({"rawtypes","unchecked"}) private JpaRepository repo(String type){return switch(type.toLowerCase()){case "fabrics"->fabrics;case "colors"->colors;case "processes"->processes;case "finishes"->finishes;case "units"->units;case "machines"->machines;default->throw new IllegalArgumentException("Maestro no soportado: "+type);};}
 private Object convert(String type,Map<String,Object> r,AbstractMaster current){
  AbstractMaster x=current;
  if(x==null)x=switch(type.toLowerCase()){case "fabrics"->new Fabric();case "colors"->new DyeColor();case "processes"->new ProcessType();case "finishes"->new FinishType();case "units"->new MeasurementUnit();case "machines"->new Machine();default->throw new IllegalArgumentException("Maestro no soportado");};
  if(r.containsKey("code"))x.setCode(String.valueOf(r.get("code"))); if(r.containsKey("name"))x.setName(String.valueOf(r.get("name"))); if(r.containsKey("active"))x.setActive(Boolean.parseBoolean(String.valueOf(r.get("active"))));
  if(x instanceof Fabric f){f.setComposition(str(r,"composition",f.getComposition()));f.setGsm(dbl(r,"gsm",f.getGsm()));f.setWidthCm(dbl(r,"widthCm",f.getWidthCm()));f.setDefaultUnit(str(r,"defaultUnit",f.getDefaultUnit()));}
  if(x instanceof DyeColor c){c.setFamily(str(r,"family",c.getFamily()));c.setPantone(str(r,"pantone",c.getPantone()));c.setSurchargePct(dbl(r,"surchargePct",c.getSurchargePct()));}
  if(x instanceof ProcessType p){p.setBillingUnit(str(r,"billingUnit",p.getBillingUnit()));p.setBasePrice(dec(r,"basePrice",p.getBasePrice()));p.setMinimumLot(dec(r,"minimumLot",p.getMinimumLot()));}
  if(x instanceof FinishType f){f.setBillingUnit(str(r,"billingUnit",f.getBillingUnit()));f.setAdditionalPrice(dec(r,"additionalPrice",f.getAdditionalPrice()));}
  if(x instanceof MeasurementUnit u){u.setSymbol(str(r,"symbol",u.getSymbol()));u.setDimension(str(r,"dimension",u.getDimension()));}
  if(x instanceof Machine m){m.setMinCapacityKg(dec(r,"minCapacityKg",m.getMinCapacityKg()));m.setMaxCapacityKg(dec(r,"maxCapacityKg",m.getMaxCapacityKg()));m.setCompatibleProcesses(str(r,"compatibleProcesses",m.getCompatibleProcesses()));m.setCostPerHour(dec(r,"costPerHour",m.getCostPerHour()));m.setBrand(str(r,"brand",m.getBrand()));m.setModel(str(r,"model",m.getModel()));m.setSerialNumber(str(r,"serialNumber",m.getSerialNumber()));m.setLocation(str(r,"location",m.getLocation()));}
  return x;
 }
 private String str(Map<String,Object>r,String k,String d){return r.containsKey(k)&&r.get(k)!=null?String.valueOf(r.get(k)):d;} private Double dbl(Map<String,Object>r,String k,Double d){try{return r.containsKey(k)&&r.get(k)!=null?Double.valueOf(String.valueOf(r.get(k))):d;}catch(Exception e){return d;}} private java.math.BigDecimal dec(Map<String,Object>r,String k,java.math.BigDecimal d){try{return r.containsKey(k)&&r.get(k)!=null?new java.math.BigDecimal(String.valueOf(r.get(k))):d;}catch(Exception e){return d;}}
}
