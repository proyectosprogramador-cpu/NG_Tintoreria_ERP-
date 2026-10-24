package com.ngtintoreria.erp.laboratory;
import jakarta.persistence.*; import java.math.BigDecimal; import java.util.UUID;
@Entity @Table(name="formula_components")
public class FormulaComponent {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id; @Column(nullable=false) private UUID versionId; @Column(length=60) private String materialCode; @Column(nullable=false,length=180) private String materialName; @Column(nullable=false,precision=14,scale=6) private BigDecimal dosage; @Column(nullable=false,length=20) private String dosageUnit; @Column(nullable=false) private int sequenceOrder=1;
 public UUID getId(){return id;} public UUID getVersionId(){return versionId;} public void setVersionId(UUID v){versionId=v;} public String getMaterialCode(){return materialCode;} public void setMaterialCode(String v){materialCode=v;} public String getMaterialName(){return materialName;} public void setMaterialName(String v){materialName=v;} public BigDecimal getDosage(){return dosage;} public void setDosage(BigDecimal v){dosage=v;} public String getDosageUnit(){return dosageUnit;} public void setDosageUnit(String v){dosageUnit=v;} public int getSequenceOrder(){return sequenceOrder;} public void setSequenceOrder(int v){sequenceOrder=v;}
}
