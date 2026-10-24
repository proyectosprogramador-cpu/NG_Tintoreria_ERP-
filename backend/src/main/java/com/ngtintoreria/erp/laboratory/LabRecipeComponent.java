package com.ngtintoreria.erp.laboratory;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
@Entity @Table(name="lab_recipe_components",uniqueConstraints=@UniqueConstraint(columnNames={"recipe_version_id","line_no"}))
public class LabRecipeComponent {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="recipe_version_id",nullable=false) private UUID recipeVersionId;
 @Column(name="line_no",nullable=false) private int lineNo;
 @Column(name="material_code",length=60) private String materialCode;
 @Column(name="material_name",nullable=false,length=180) private String materialName;
 @Column(nullable=false,precision=14,scale=6) private BigDecimal dosage;
 @Column(name="dosage_unit",nullable=false,length=20) private String dosageUnit;
 @Column(name="calculated_quantity_kg",nullable=false,precision=16,scale=6) private BigDecimal calculatedQuantityKg;
 @Column(length=500) private String notes;
 public UUID getId(){return id;} public UUID getRecipeVersionId(){return recipeVersionId;} public void setRecipeVersionId(UUID v){recipeVersionId=v;}
 public int getLineNo(){return lineNo;} public void setLineNo(int v){lineNo=v;} public String getMaterialCode(){return materialCode;} public void setMaterialCode(String v){materialCode=v;}
 public String getMaterialName(){return materialName;} public void setMaterialName(String v){materialName=v;} public BigDecimal getDosage(){return dosage;} public void setDosage(BigDecimal v){dosage=v;}
 public String getDosageUnit(){return dosageUnit;} public void setDosageUnit(String v){dosageUnit=v;} public BigDecimal getCalculatedQuantityKg(){return calculatedQuantityKg;} public void setCalculatedQuantityKg(BigDecimal v){calculatedQuantityKg=v;}
 public String getNotes(){return notes;} public void setNotes(String v){notes=v;}
}
