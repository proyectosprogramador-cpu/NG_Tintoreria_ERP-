package com.ngtintoreria.erp.laboratory;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="lab_recipe_versions",uniqueConstraints=@UniqueConstraint(columnNames={"lab_request_id","alternative_code","version_no"}))
public class LabRecipeVersion {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="lab_request_id",nullable=false) private UUID labRequestId;
 @Column(name="alternative_code",nullable=false,length=1) private String alternativeCode;
 @Column(name="version_no",nullable=false) private int versionNo;
 @Column(nullable=false,length=20) private String status="DRAFT";
 @Column(name="actual_weight_kg",nullable=false,precision=16,scale=3) private BigDecimal actualWeightKg;
 @Column(name="bath_ratio",nullable=false,precision=8,scale=3) private BigDecimal bathRatio;
 @Column(name="bath_volume_l",nullable=false,precision=16,scale=3) private BigDecimal bathVolumeL;
 @Column(name="temperature_c",precision=8,scale=2) private BigDecimal temperatureC;
 @Column(name="time_minutes") private Integer timeMinutes;
 @Column(precision=6,scale=2) private BigDecimal ph;
 @Column(name="curve_text",columnDefinition="text") private String curveText;
 @Column(name="change_reason",length=500) private String changeReason;
 @Column(name="formula_id") private UUID formulaId;
 @Column(name="formula_version_id") private UUID formulaVersionId;
 @Column(name="created_by",nullable=false,length=160) private String createdBy;
 @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
 @Column(name="released_by",length=160) private String releasedBy;
 @Column(name="released_at") private OffsetDateTime releasedAt;
 @PrePersist void pre(){createdAt=OffsetDateTime.now();}
 public UUID getId(){return id;} public UUID getLabRequestId(){return labRequestId;} public void setLabRequestId(UUID v){labRequestId=v;}
 public String getAlternativeCode(){return alternativeCode;} public void setAlternativeCode(String v){alternativeCode=v;} public int getVersionNo(){return versionNo;} public void setVersionNo(int v){versionNo=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;} public BigDecimal getActualWeightKg(){return actualWeightKg;} public void setActualWeightKg(BigDecimal v){actualWeightKg=v;}
 public BigDecimal getBathRatio(){return bathRatio;} public void setBathRatio(BigDecimal v){bathRatio=v;} public BigDecimal getBathVolumeL(){return bathVolumeL;} public void setBathVolumeL(BigDecimal v){bathVolumeL=v;}
 public BigDecimal getTemperatureC(){return temperatureC;} public void setTemperatureC(BigDecimal v){temperatureC=v;} public Integer getTimeMinutes(){return timeMinutes;} public void setTimeMinutes(Integer v){timeMinutes=v;}
 public BigDecimal getPh(){return ph;} public void setPh(BigDecimal v){ph=v;} public String getCurveText(){return curveText;} public void setCurveText(String v){curveText=v;}
 public String getChangeReason(){return changeReason;} public void setChangeReason(String v){changeReason=v;} public UUID getFormulaId(){return formulaId;} public void setFormulaId(UUID v){formulaId=v;}
 public UUID getFormulaVersionId(){return formulaVersionId;} public void setFormulaVersionId(UUID v){formulaVersionId=v;} public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
 public OffsetDateTime getCreatedAt(){return createdAt;} public String getReleasedBy(){return releasedBy;} public void setReleasedBy(String v){releasedBy=v;} public OffsetDateTime getReleasedAt(){return releasedAt;} public void setReleasedAt(OffsetDateTime v){releasedAt=v;}
}
