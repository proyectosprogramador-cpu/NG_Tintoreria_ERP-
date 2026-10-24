package com.ngtintoreria.erp.production;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="production_material_requirements",uniqueConstraints=@UniqueConstraint(columnNames={"production_order_id","line_no"}))
public class ProductionMaterialRequirement {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="production_order_id",nullable=false) private UUID productionOrderId;
 @Column(name="line_no",nullable=false) private int lineNo;
 @Column(name="material_code",length=60) private String materialCode;
 @Column(name="material_name",nullable=false,length=180) private String materialName;
 @Column(name="inventory_item_id") private UUID inventoryItemId;
 @Column(nullable=false,precision=14,scale=6) private BigDecimal dosage;
 @Column(name="dosage_unit",nullable=false,length=20) private String dosageUnit;
 @Column(name="planned_quantity_kg",nullable=false,precision=16,scale=6) private BigDecimal plannedQuantityKg;
 @Column(name="available_quantity_kg",precision=16,scale=6) private BigDecimal availableQuantityKg;
 @Column(name="shortage_quantity_kg",nullable=false,precision=16,scale=6) private BigDecimal shortageQuantityKg=BigDecimal.ZERO;
 @Column(name="stock_status",nullable=false,length=20) private String stockStatus="PENDING";
 @Column(name="actual_quantity_kg",nullable=false,precision=16,scale=6) private BigDecimal actualQuantityKg=BigDecimal.ZERO;
 @Column(name="consumption_variance_kg",nullable=false,precision=16,scale=6) private BigDecimal consumptionVarianceKg=BigDecimal.ZERO;
 @Column(name="consumption_confirmed",nullable=false) private boolean consumptionConfirmed=false;
 @Column(name="consumption_confirmed_by",length=160) private String consumptionConfirmedBy;
 @Column(name="consumption_confirmed_at") private OffsetDateTime consumptionConfirmedAt;
 @Column(name="warehouse_comment",length=500) private String warehouseComment;
 @Column(name="checked_by",length=160) private String checkedBy;
 @Column(name="checked_at") private OffsetDateTime checkedAt;
 public UUID getId(){return id;} public UUID getProductionOrderId(){return productionOrderId;} public void setProductionOrderId(UUID v){productionOrderId=v;} public int getLineNo(){return lineNo;} public void setLineNo(int v){lineNo=v;}
 public String getMaterialCode(){return materialCode;} public void setMaterialCode(String v){materialCode=v;} public String getMaterialName(){return materialName;} public void setMaterialName(String v){materialName=v;}
 public UUID getInventoryItemId(){return inventoryItemId;} public void setInventoryItemId(UUID v){inventoryItemId=v;}
 public BigDecimal getDosage(){return dosage;} public void setDosage(BigDecimal v){dosage=v;} public String getDosageUnit(){return dosageUnit;} public void setDosageUnit(String v){dosageUnit=v;}
 public BigDecimal getPlannedQuantityKg(){return plannedQuantityKg;} public void setPlannedQuantityKg(BigDecimal v){plannedQuantityKg=v;} public BigDecimal getAvailableQuantityKg(){return availableQuantityKg;} public void setAvailableQuantityKg(BigDecimal v){availableQuantityKg=v;}
 public BigDecimal getShortageQuantityKg(){return shortageQuantityKg;} public void setShortageQuantityKg(BigDecimal v){shortageQuantityKg=v;} public String getStockStatus(){return stockStatus;} public void setStockStatus(String v){stockStatus=v;}
 public BigDecimal getActualQuantityKg(){return actualQuantityKg;} public void setActualQuantityKg(BigDecimal v){actualQuantityKg=v;} public BigDecimal getConsumptionVarianceKg(){return consumptionVarianceKg;} public void setConsumptionVarianceKg(BigDecimal v){consumptionVarianceKg=v;} public boolean isConsumptionConfirmed(){return consumptionConfirmed;} public void setConsumptionConfirmed(boolean v){consumptionConfirmed=v;} public String getConsumptionConfirmedBy(){return consumptionConfirmedBy;} public void setConsumptionConfirmedBy(String v){consumptionConfirmedBy=v;} public OffsetDateTime getConsumptionConfirmedAt(){return consumptionConfirmedAt;} public void setConsumptionConfirmedAt(OffsetDateTime v){consumptionConfirmedAt=v;}
 public String getWarehouseComment(){return warehouseComment;} public void setWarehouseComment(String v){warehouseComment=v;} public String getCheckedBy(){return checkedBy;} public void setCheckedBy(String v){checkedBy=v;} public OffsetDateTime getCheckedAt(){return checkedAt;} public void setCheckedAt(OffsetDateTime v){checkedAt=v;}
}
