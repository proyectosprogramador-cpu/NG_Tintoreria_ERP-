package com.ngtintoreria.erp.laboratory;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="lab_alternatives",uniqueConstraints=@UniqueConstraint(columnNames={"lab_request_id","alternative_code"}))
public class LabAlternative {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="lab_request_id",nullable=false) private UUID labRequestId;
 @Column(name="alternative_code",nullable=false,length=1) private String alternativeCode;
 @Column(name="l_value",precision=8,scale=3) private BigDecimal lValue;
 @Column(name="a_value",precision=8,scale=3) private BigDecimal aValue;
 @Column(name="b_value",precision=8,scale=3) private BigDecimal bValue;
 @Column(name="delta_e",precision=8,scale=3) private BigDecimal deltaE;
 @Column(name="visual_result",nullable=false,length=40) private String visualResult="PENDING";
 @Column(length=500) private String notes;
 @Column(name="updated_at",nullable=false) private OffsetDateTime updatedAt;
 @PrePersist @PreUpdate void touch(){updatedAt=OffsetDateTime.now();}
 public UUID getId(){return id;} public UUID getLabRequestId(){return labRequestId;} public void setLabRequestId(UUID v){labRequestId=v;}
 public String getAlternativeCode(){return alternativeCode;} public void setAlternativeCode(String v){alternativeCode=v;}
 public BigDecimal getLValue(){return lValue;} public void setLValue(BigDecimal v){lValue=v;} public BigDecimal getAValue(){return aValue;} public void setAValue(BigDecimal v){aValue=v;}
 public BigDecimal getBValue(){return bValue;} public void setBValue(BigDecimal v){bValue=v;} public BigDecimal getDeltaE(){return deltaE;} public void setDeltaE(BigDecimal v){deltaE=v;}
 public String getVisualResult(){return visualResult;} public void setVisualResult(String v){visualResult=v;} public String getNotes(){return notes;} public void setNotes(String v){notes=v;}
 public OffsetDateTime getUpdatedAt(){return updatedAt;}
}
