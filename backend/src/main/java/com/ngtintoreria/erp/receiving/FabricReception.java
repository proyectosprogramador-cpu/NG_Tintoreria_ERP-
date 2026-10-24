package com.ngtintoreria.erp.receiving;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "fabric_receptions")
public class FabricReception {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name="reception_number",nullable=false,unique=true,length=30) private String receptionNumber;
    @Column(name="sales_order_id",nullable=false) private UUID salesOrderId;
    @Column(name="client_id",nullable=false) private UUID clientId;
    @Column(name="status",nullable=false,length=30) private String status="DRAFT";
    @Column(name="received_date",nullable=false) private LocalDate receivedDate;
    @Column(name="client_guide_number",length=80) private String clientGuideNumber;
    @Column(name="vehicle_plate",length=20) private String vehiclePlate;
    @Column(name="delivered_by",length=180) private String deliveredBy;
    @Column(name="received_by",length=180) private String receivedBy;
    @Column(name="warehouse_location",length=120) private String warehouseLocation;
    @Column(name="notes",columnDefinition="text") private String notes;
    @Column(name="roll_count",nullable=false) private int rollCount;
    @Column(name="total_declared_kg",nullable=false,precision=16,scale=3) private BigDecimal totalDeclaredKg=BigDecimal.ZERO;
    @Column(name="total_gross_kg",nullable=false,precision=16,scale=3) private BigDecimal totalGrossKg=BigDecimal.ZERO;
    @Column(name="total_tare_kg",nullable=false,precision=16,scale=3) private BigDecimal totalTareKg=BigDecimal.ZERO;
    @Column(name="total_net_kg",nullable=false,precision=16,scale=3) private BigDecimal totalNetKg=BigDecimal.ZERO;
    @Column(name="total_meters",nullable=false,precision=16,scale=3) private BigDecimal totalMeters=BigDecimal.ZERO;
    @Column(name="version_no",nullable=false) private int versionNo=1;
    @Column(name="created_by",nullable=false,length=160) private String createdBy;
    @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) private OffsetDateTime updatedAt;
    @Column(name="released_at") private OffsetDateTime releasedAt;

    @PrePersist void pre(){createdAt=updatedAt=OffsetDateTime.now();}
    @PreUpdate void upd(){updatedAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public String getReceptionNumber(){return receptionNumber;} public void setReceptionNumber(String v){receptionNumber=v;}
    public UUID getSalesOrderId(){return salesOrderId;} public void setSalesOrderId(UUID v){salesOrderId=v;} public UUID getClientId(){return clientId;} public void setClientId(UUID v){clientId=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;} public LocalDate getReceivedDate(){return receivedDate;} public void setReceivedDate(LocalDate v){receivedDate=v;}
    public String getClientGuideNumber(){return clientGuideNumber;} public void setClientGuideNumber(String v){clientGuideNumber=v;} public String getVehiclePlate(){return vehiclePlate;} public void setVehiclePlate(String v){vehiclePlate=v;}
    public String getDeliveredBy(){return deliveredBy;} public void setDeliveredBy(String v){deliveredBy=v;} public String getReceivedBy(){return receivedBy;} public void setReceivedBy(String v){receivedBy=v;}
    public String getWarehouseLocation(){return warehouseLocation;} public void setWarehouseLocation(String v){warehouseLocation=v;} public String getNotes(){return notes;} public void setNotes(String v){notes=v;}
    public int getRollCount(){return rollCount;} public void setRollCount(int v){rollCount=v;} public BigDecimal getTotalDeclaredKg(){return totalDeclaredKg;} public void setTotalDeclaredKg(BigDecimal v){totalDeclaredKg=v;}
    public BigDecimal getTotalGrossKg(){return totalGrossKg;} public void setTotalGrossKg(BigDecimal v){totalGrossKg=v;} public BigDecimal getTotalTareKg(){return totalTareKg;} public void setTotalTareKg(BigDecimal v){totalTareKg=v;}
    public BigDecimal getTotalNetKg(){return totalNetKg;} public void setTotalNetKg(BigDecimal v){totalNetKg=v;} public BigDecimal getTotalMeters(){return totalMeters;} public void setTotalMeters(BigDecimal v){totalMeters=v;}
    public int getVersionNo(){return versionNo;} public void setVersionNo(int v){versionNo=v;} public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
    public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;} public OffsetDateTime getReleasedAt(){return releasedAt;} public void setReleasedAt(OffsetDateTime v){releasedAt=v;}
}
