package com.ngtintoreria.erp.receiving;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name="reception_rolls")
public class ReceptionRoll {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="reception_id",nullable=false) private UUID receptionId;
    @Column(name="sales_order_item_id",nullable=false) private UUID salesOrderItemId;
    @Column(name="line_no",nullable=false) private int lineNo;
    @Column(name="roll_code",nullable=false,unique=true,length=40) private String rollCode;
    @Column(name="client_roll_code",length=80) private String clientRollCode;
    @Column(name="fabric_id",nullable=false) private UUID fabricId;
    @Column(name="ownership",nullable=false,length=20) private String ownership="CLIENT";
    @Column(name="declared_kg",nullable=false,precision=14,scale=3) private BigDecimal declaredKg=BigDecimal.ZERO;
    @Column(name="gross_kg",nullable=false,precision=14,scale=3) private BigDecimal grossKg=BigDecimal.ZERO;
    @Column(name="tare_kg",nullable=false,precision=14,scale=3) private BigDecimal tareKg=BigDecimal.ZERO;
    @Column(name="net_kg",nullable=false,precision=14,scale=3) private BigDecimal netKg=BigDecimal.ZERO;
    @Column(name="meters",nullable=false,precision=14,scale=3) private BigDecimal meters=BigDecimal.ZERO;
    @Column(name="width_cm",precision=10,scale=2) private BigDecimal widthCm;
    @Column(name="gsm",precision=10,scale=2) private BigDecimal gsm;
    @Column(name="inspection_status",nullable=false,length=20) private String inspectionStatus="PENDING";
    @Column(name="defect_description",length=500) private String defectDescription;
    @Column(name="observations",length=500) private String observations;
    @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) private OffsetDateTime updatedAt;
    @PrePersist void pre(){createdAt=updatedAt=OffsetDateTime.now();} @PreUpdate void upd(){updatedAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public UUID getReceptionId(){return receptionId;} public void setReceptionId(UUID v){receptionId=v;} public UUID getSalesOrderItemId(){return salesOrderItemId;} public void setSalesOrderItemId(UUID v){salesOrderItemId=v;}
    public int getLineNo(){return lineNo;} public void setLineNo(int v){lineNo=v;} public String getRollCode(){return rollCode;} public void setRollCode(String v){rollCode=v;} public String getClientRollCode(){return clientRollCode;} public void setClientRollCode(String v){clientRollCode=v;}
    public UUID getFabricId(){return fabricId;} public void setFabricId(UUID v){fabricId=v;} public String getOwnership(){return ownership;} public void setOwnership(String v){ownership=v;}
    public BigDecimal getDeclaredKg(){return declaredKg;} public void setDeclaredKg(BigDecimal v){declaredKg=v;} public BigDecimal getGrossKg(){return grossKg;} public void setGrossKg(BigDecimal v){grossKg=v;} public BigDecimal getTareKg(){return tareKg;} public void setTareKg(BigDecimal v){tareKg=v;}
    public BigDecimal getNetKg(){return netKg;} public void setNetKg(BigDecimal v){netKg=v;} public BigDecimal getMeters(){return meters;} public void setMeters(BigDecimal v){meters=v;} public BigDecimal getWidthCm(){return widthCm;} public void setWidthCm(BigDecimal v){widthCm=v;} public BigDecimal getGsm(){return gsm;} public void setGsm(BigDecimal v){gsm=v;}
    public String getInspectionStatus(){return inspectionStatus;} public void setInspectionStatus(String v){inspectionStatus=v;} public String getDefectDescription(){return defectDescription;} public void setDefectDescription(String v){defectDescription=v;} public String getObservations(){return observations;} public void setObservations(String v){observations=v;}
    public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;}
}
