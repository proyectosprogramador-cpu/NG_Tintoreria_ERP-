package com.ngtintoreria.erp.production;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="production_orders")
public class ProductionOrder {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="production_number",nullable=false,unique=true,length=30) private String productionNumber;
 @Column(name="batch_number",nullable=false,unique=true,length=40) private String batchNumber;
 @Column(name="lab_request_id",nullable=false) private UUID labRequestId;
 @Column(name="lab_recipe_version_id",nullable=false) private UUID labRecipeVersionId;
 @Column(name="formula_id",nullable=false) private UUID formulaId;
 @Column(name="formula_version_id",nullable=false) private UUID formulaVersionId;
 @Column(name="sales_order_id",nullable=false) private UUID salesOrderId;
 @Column(name="sales_order_item_id",nullable=false) private UUID salesOrderItemId;
 @Column(name="client_id",nullable=false) private UUID clientId;
 @Column(name="machine_id",nullable=false) private UUID machineId;
 @Column(name="source_return_id") private UUID sourceReturnId;
 @Column(nullable=false,length=30) private String status="PLANNED";
 @Column(name="material_status",nullable=false,length=20) private String materialStatus="PENDING";
 @Column(nullable=false,length=20) private String priority="NORMAL";
 @Column(name="shift_code",nullable=false,length=20) private String shiftCode="MORNING";
 @Column(name="operator_name",length=160) private String operatorName;
 @Column(name="color_family",length=100) private String colorFamily;
 @Column(name="batch_weight_kg",nullable=false,precision=16,scale=3) private BigDecimal batchWeightKg;
 @Column(name="bath_volume_l",nullable=false,precision=16,scale=3) private BigDecimal bathVolumeL;
 @Column(name="scheduled_start",nullable=false) private OffsetDateTime scheduledStart;
 @Column(name="scheduled_end",nullable=false) private OffsetDateTime scheduledEnd;
 @Column(name="estimated_minutes",nullable=false) private int estimatedMinutes;
 @Column(name="setup_minutes",nullable=false) private int setupMinutes;
 @Column(name="cleaning_minutes",nullable=false) private int cleaningMinutes;
 @Column(name="actual_start") private OffsetDateTime actualStart;
 @Column(name="actual_end") private OffsetDateTime actualEnd;
 @Column(name="actual_output_weight_kg",precision=16,scale=3) private BigDecimal actualOutputWeightKg;
 @Column(name="reprocess_weight_kg",nullable=false,precision=16,scale=3) private BigDecimal reprocessWeightKg=BigDecimal.ZERO;
 @Column(name="waste_weight_kg",nullable=false,precision=16,scale=3) private BigDecimal wasteWeightKg=BigDecimal.ZERO;
 @Column(name="water_consumption_l",precision=16,scale=3) private BigDecimal waterConsumptionL;
 @Column(name="energy_kwh",precision=16,scale=3) private BigDecimal energyKwh;
 @Column(name="execution_notes",columnDefinition="text") private String executionNotes;
 @Column(columnDefinition="text") private String notes;
 @Column(name="pause_reason",length=500) private String pauseReason;
 @Column(name="version_no",nullable=false) private int versionNo=1;
 @Column(name="created_by",nullable=false,length=160) private String createdBy;
 @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
 @Column(name="updated_at",nullable=false) private OffsetDateTime updatedAt;
 @PrePersist void pre(){createdAt=updatedAt=OffsetDateTime.now();} @PreUpdate void upd(){updatedAt=OffsetDateTime.now();}
 public UUID getId(){return id;} public String getProductionNumber(){return productionNumber;} public void setProductionNumber(String v){productionNumber=v;} public String getBatchNumber(){return batchNumber;} public void setBatchNumber(String v){batchNumber=v;}
 public UUID getLabRequestId(){return labRequestId;} public void setLabRequestId(UUID v){labRequestId=v;} public UUID getLabRecipeVersionId(){return labRecipeVersionId;} public void setLabRecipeVersionId(UUID v){labRecipeVersionId=v;}
 public UUID getFormulaId(){return formulaId;} public void setFormulaId(UUID v){formulaId=v;} public UUID getFormulaVersionId(){return formulaVersionId;} public void setFormulaVersionId(UUID v){formulaVersionId=v;}
 public UUID getSalesOrderId(){return salesOrderId;} public void setSalesOrderId(UUID v){salesOrderId=v;} public UUID getSalesOrderItemId(){return salesOrderItemId;} public void setSalesOrderItemId(UUID v){salesOrderItemId=v;}
 public UUID getClientId(){return clientId;} public void setClientId(UUID v){clientId=v;} public UUID getMachineId(){return machineId;} public void setMachineId(UUID v){machineId=v;}
 public UUID getSourceReturnId(){return sourceReturnId;} public void setSourceReturnId(UUID v){sourceReturnId=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getMaterialStatus(){return materialStatus;} public void setMaterialStatus(String v){materialStatus=v;}
 public String getPriority(){return priority;} public void setPriority(String v){priority=v;} public String getShiftCode(){return shiftCode;} public void setShiftCode(String v){shiftCode=v;}
 public String getOperatorName(){return operatorName;} public void setOperatorName(String v){operatorName=v;} public String getColorFamily(){return colorFamily;} public void setColorFamily(String v){colorFamily=v;}
 public BigDecimal getBatchWeightKg(){return batchWeightKg;} public void setBatchWeightKg(BigDecimal v){batchWeightKg=v;} public BigDecimal getBathVolumeL(){return bathVolumeL;} public void setBathVolumeL(BigDecimal v){bathVolumeL=v;}
 public OffsetDateTime getScheduledStart(){return scheduledStart;} public void setScheduledStart(OffsetDateTime v){scheduledStart=v;} public OffsetDateTime getScheduledEnd(){return scheduledEnd;} public void setScheduledEnd(OffsetDateTime v){scheduledEnd=v;}
 public int getEstimatedMinutes(){return estimatedMinutes;} public void setEstimatedMinutes(int v){estimatedMinutes=v;} public int getSetupMinutes(){return setupMinutes;} public void setSetupMinutes(int v){setupMinutes=v;} public int getCleaningMinutes(){return cleaningMinutes;} public void setCleaningMinutes(int v){cleaningMinutes=v;}
 public OffsetDateTime getActualStart(){return actualStart;} public void setActualStart(OffsetDateTime v){actualStart=v;} public OffsetDateTime getActualEnd(){return actualEnd;} public void setActualEnd(OffsetDateTime v){actualEnd=v;}
 public BigDecimal getActualOutputWeightKg(){return actualOutputWeightKg;} public void setActualOutputWeightKg(BigDecimal v){actualOutputWeightKg=v;} public BigDecimal getReprocessWeightKg(){return reprocessWeightKg;} public void setReprocessWeightKg(BigDecimal v){reprocessWeightKg=v;} public BigDecimal getWasteWeightKg(){return wasteWeightKg;} public void setWasteWeightKg(BigDecimal v){wasteWeightKg=v;} public BigDecimal getWaterConsumptionL(){return waterConsumptionL;} public void setWaterConsumptionL(BigDecimal v){waterConsumptionL=v;} public BigDecimal getEnergyKwh(){return energyKwh;} public void setEnergyKwh(BigDecimal v){energyKwh=v;} public String getExecutionNotes(){return executionNotes;} public void setExecutionNotes(String v){executionNotes=v;}
 public String getNotes(){return notes;} public void setNotes(String v){notes=v;} public String getPauseReason(){return pauseReason;} public void setPauseReason(String v){pauseReason=v;}
 public int getVersionNo(){return versionNo;} public void setVersionNo(int v){versionNo=v;} public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
 public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;}
}
