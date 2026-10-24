package com.ngtintoreria.erp.laboratory;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name="lab_requests")
public class ColorLabRequest {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="lab_number",nullable=false,unique=true,length=30) private String labNumber;
    @Column(name="source_reception_id",nullable=false) private UUID sourceReceptionId;
    @Column(name="sales_order_id",nullable=false) private UUID salesOrderId;
    @Column(name="sales_order_item_id",nullable=false) private UUID salesOrderItemId;
    @Column(name="client_id",nullable=false) private UUID clientId;
    @Column(name="fabric_id",nullable=false) private UUID fabricId;
    @Column(name="color_id",nullable=false) private UUID colorId;
    @Column(name="process_id",nullable=false) private UUID processId;
    @Column(name="finish_id") private UUID finishId;
    @Column(nullable=false,length=30) private String status="IN_DEVELOPMENT";
    @Column(name="reference_method",nullable=false,length=100) private String referenceMethod;
    @Column(name="color_standard",nullable=false,length=250) private String colorStandard;
    @Column(nullable=false,length=30) private String illuminant="D65";
    @Column(nullable=false,length=250) private String substrate;
    @Column(name="actual_weight_kg",nullable=false,precision=16,scale=3) private BigDecimal actualWeightKg;
    @Column(columnDefinition="text") private String observations;
    @Column(name="selected_alternative",length=1) private String selectedAlternative;
    @Column(name="released_recipe_id") private UUID releasedRecipeId;
    @Column(name="version_no",nullable=false) private int versionNo=1;
    @Column(name="created_by",nullable=false,length=160) private String createdBy;
    @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) private OffsetDateTime updatedAt;
    @Column(name="sent_at") private OffsetDateTime sentAt;
    @Column(name="approved_at") private OffsetDateTime approvedAt;
    @Column(name="rejected_at") private OffsetDateTime rejectedAt;
    @PrePersist void pre(){createdAt=updatedAt=OffsetDateTime.now();}
    @PreUpdate void upd(){updatedAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public String getLabNumber(){return labNumber;} public void setLabNumber(String v){labNumber=v;}
    public UUID getSourceReceptionId(){return sourceReceptionId;} public void setSourceReceptionId(UUID v){sourceReceptionId=v;}
    public UUID getSalesOrderId(){return salesOrderId;} public void setSalesOrderId(UUID v){salesOrderId=v;} public UUID getSalesOrderItemId(){return salesOrderItemId;} public void setSalesOrderItemId(UUID v){salesOrderItemId=v;}
    public UUID getClientId(){return clientId;} public void setClientId(UUID v){clientId=v;} public UUID getFabricId(){return fabricId;} public void setFabricId(UUID v){fabricId=v;}
    public UUID getColorId(){return colorId;} public void setColorId(UUID v){colorId=v;} public UUID getProcessId(){return processId;} public void setProcessId(UUID v){processId=v;}
    public UUID getFinishId(){return finishId;} public void setFinishId(UUID v){finishId=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getReferenceMethod(){return referenceMethod;} public void setReferenceMethod(String v){referenceMethod=v;} public String getColorStandard(){return colorStandard;} public void setColorStandard(String v){colorStandard=v;}
    public String getIlluminant(){return illuminant;} public void setIlluminant(String v){illuminant=v;} public String getSubstrate(){return substrate;} public void setSubstrate(String v){substrate=v;}
    public BigDecimal getActualWeightKg(){return actualWeightKg;} public void setActualWeightKg(BigDecimal v){actualWeightKg=v;} public String getObservations(){return observations;} public void setObservations(String v){observations=v;}
    public String getSelectedAlternative(){return selectedAlternative;} public void setSelectedAlternative(String v){selectedAlternative=v;} public UUID getReleasedRecipeId(){return releasedRecipeId;} public void setReleasedRecipeId(UUID v){releasedRecipeId=v;}
    public int getVersionNo(){return versionNo;} public void setVersionNo(int v){versionNo=v;} public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
    public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;} public OffsetDateTime getSentAt(){return sentAt;} public void setSentAt(OffsetDateTime v){sentAt=v;}
    public OffsetDateTime getApprovedAt(){return approvedAt;} public void setApprovedAt(OffsetDateTime v){approvedAt=v;} public OffsetDateTime getRejectedAt(){return rejectedAt;} public void setRejectedAt(OffsetDateTime v){rejectedAt=v;}
}
