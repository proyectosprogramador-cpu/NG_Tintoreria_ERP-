package com.ngtintoreria.erp.laboratory;
import com.ngtintoreria.erp.audit.AuditService;
import jakarta.transaction.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal; import java.time.OffsetDateTime; import java.util.*;

@RestController @RequestMapping("/api/formulas")
public class FormulaController {
 private final FormulaRepository formulas; private final FormulaVersionRepository versions; private final FormulaComponentRepository components; private final AuditService audit;
 public FormulaController(FormulaRepository formulas,FormulaVersionRepository versions,FormulaComponentRepository components,AuditService audit){this.formulas=formulas;this.versions=versions;this.components=components;this.audit=audit;}
 public record ComponentRequest(String materialCode,String materialName,BigDecimal dosage,String dosageUnit,Integer sequenceOrder){}
 public record FormulaRequest(String code,String name,UUID clientId,UUID fabricId,UUID colorId,UUID processId,UUID finishId,BigDecimal bathRatio,BigDecimal temperatureC,Integer timeMinutes,BigDecimal ph,String curveText,List<ComponentRequest> components){}
 public record VersionDto(UUID id,int versionNo,String status,BigDecimal bathRatio,BigDecimal temperatureC,Integer timeMinutes,BigDecimal ph,String curveText,List<FormulaComponent> components,OffsetDateTime createdAt,OffsetDateTime releasedAt){}
 public record FormulaDto(Formula formula,VersionDto latest){}
 @GetMapping @PreAuthorize("hasAuthority('FORMULAS_READ')") public List<FormulaDto> list(){return formulas.findAll().stream().sorted(Comparator.comparing(Formula::getName,String.CASE_INSENSITIVE_ORDER)).map(this::dto).toList();}
 @GetMapping("/{id}") @PreAuthorize("hasAuthority('FORMULAS_READ')") public FormulaDto one(@PathVariable UUID id){return dto(formulas.findById(id).orElseThrow());}
 @PostMapping @Transactional @PreAuthorize("hasAuthority('FORMULAS_WRITE')") public FormulaDto create(@RequestBody FormulaRequest r,Authentication auth){if(formulas.existsByCode(r.code()))throw new IllegalArgumentException("Código de fórmula ya existe");Formula f=new Formula();apply(f,r);f.setCurrentVersion(1);f=formulas.save(f);FormulaVersion v=makeVersion(f.getId(),1,r,auth.getName());v=versions.save(v);saveComponents(v.getId(),r.components());audit.record("FORMULA_CREADA","Fórmulas",f.getId().toString(),f.getCode()+" V01");return dto(f);}
 @PostMapping("/{id}/versions") @Transactional @PreAuthorize("hasAuthority('FORMULAS_WRITE')") public FormulaDto newVersion(@PathVariable UUID id,@RequestBody FormulaRequest r,Authentication auth){Formula f=formulas.findById(id).orElseThrow();int next=f.getCurrentVersion()+1;apply(f,r);f.setCurrentVersion(next);f=formulas.save(f);FormulaVersion v=versions.save(makeVersion(f.getId(),next,r,auth.getName()));saveComponents(v.getId(),r.components());audit.record("FORMULA_NUEVA_VERSION","Fórmulas",f.getId().toString(),f.getCode()+" V"+String.format("%02d",next));return dto(f);}
 @PostMapping("/versions/{versionId}/release") @PreAuthorize("hasAuthority('FORMULAS_RELEASE')") public FormulaDto release(@PathVariable UUID versionId){FormulaVersion v=versions.findById(versionId).orElseThrow();v.setStatus("RELEASED");v.setReleasedAt(OffsetDateTime.now());versions.save(v);Formula f=formulas.findById(v.getFormulaId()).orElseThrow();audit.record("FORMULA_LIBERADA","Fórmulas",f.getId().toString(),f.getCode()+" V"+String.format("%02d",v.getVersionNo()));return dto(f);}
 private void apply(Formula f,FormulaRequest r){f.setCode(r.code());f.setName(r.name());f.setClientId(r.clientId());f.setFabricId(r.fabricId());f.setColorId(r.colorId());f.setProcessId(r.processId());f.setFinishId(r.finishId());}
 private FormulaVersion makeVersion(UUID formulaId,int n,FormulaRequest r,String user){FormulaVersion v=new FormulaVersion();v.setFormulaId(formulaId);v.setVersionNo(n);v.setBathRatio(r.bathRatio());v.setTemperatureC(r.temperatureC());v.setTimeMinutes(r.timeMinutes());v.setPh(r.ph());v.setCurveText(r.curveText());v.setCreatedBy(user);return v;}
 private void saveComponents(UUID vid,List<ComponentRequest> list){if(list==null)return;int i=1;for(ComponentRequest c:list){FormulaComponent x=new FormulaComponent();x.setVersionId(vid);x.setMaterialCode(c.materialCode());x.setMaterialName(c.materialName());x.setDosage(c.dosage());x.setDosageUnit(c.dosageUnit());x.setSequenceOrder(c.sequenceOrder()==null?i:c.sequenceOrder());components.save(x);i++;}}
 private FormulaDto dto(Formula f){FormulaVersion v=versions.findFirstByFormulaIdOrderByVersionNoDesc(f.getId()).orElse(null);VersionDto vd=v==null?null:new VersionDto(v.getId(),v.getVersionNo(),v.getStatus(),v.getBathRatio(),v.getTemperatureC(),v.getTimeMinutes(),v.getPh(),v.getCurveText(),components.findByVersionIdOrderBySequenceOrder(v.getId()),v.getCreatedAt(),v.getReleasedAt());return new FormulaDto(f,vd);}
}
