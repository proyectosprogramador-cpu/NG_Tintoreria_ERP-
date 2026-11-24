package com.ngtintoreria.erp.production;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Solicitud de cancelación pendiente de autorización administrativa. */
@Entity
@Table(name="production_cancellation_requests")
public class ProductionCancellationRequest {
 @Id @GeneratedValue(strategy=GenerationType.UUID)
 private UUID id;
 @Column(name="production_order_id",nullable=false) private UUID productionOrderId;
 @Column(nullable=false,length=20) private String status="PENDING";
 @Column(nullable=false,length=500) private String reason;
 @Column(name="requested_by",nullable=false,length=160) private String requestedBy;
 @Column(name="requested_at",nullable=false,updatable=false) private OffsetDateTime requestedAt;
 @Column(name="requested_order_version",nullable=false) private int requestedOrderVersion;
 @Column(name="decided_by",length=160) private String decidedBy;
 @Column(name="decided_at") private OffsetDateTime decidedAt;
 @Column(name="decision_comment",length=500) private String decisionComment;
 @PrePersist void beforeInsert(){if(requestedAt==null)requestedAt=OffsetDateTime.now();}
 public UUID getId(){return id;}
 public UUID getProductionOrderId(){return productionOrderId;}
 public void setProductionOrderId(UUID value){productionOrderId=value;}
 public String getStatus(){return status;}
 public void setStatus(String value){status=value;}
 public String getReason(){return reason;}
 public void setReason(String value){reason=value;}
 public String getRequestedBy(){return requestedBy;}
 public void setRequestedBy(String value){requestedBy=value;}
 public OffsetDateTime getRequestedAt(){return requestedAt;}
 public int getRequestedOrderVersion(){return requestedOrderVersion;}
 public void setRequestedOrderVersion(int value){requestedOrderVersion=value;}
 public String getDecidedBy(){return decidedBy;}
 public void setDecidedBy(String value){decidedBy=value;}
 public OffsetDateTime getDecidedAt(){return decidedAt;}
 public void setDecidedAt(OffsetDateTime value){decidedAt=value;}
 public String getDecisionComment(){return decisionComment;}
 public void setDecisionComment(String value){decisionComment=value;}
}
