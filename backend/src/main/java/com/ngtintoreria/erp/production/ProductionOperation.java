package com.ngtintoreria.erp.production;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="production_operations",uniqueConstraints=@UniqueConstraint(columnNames={"production_order_id","sequence_no"}))
public class ProductionOperation {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="production_order_id",nullable=false) private UUID productionOrderId;
 @Column(name="sequence_no",nullable=false) private int sequenceNo;
 @Column(name="operation_code",nullable=false,length=40) private String operationCode;
 @Column(name="operation_name",nullable=false,length=180) private String operationName;
 @Column(name="planned_minutes",nullable=false) private int plannedMinutes;
 @Column(nullable=false,length=20) private String status="PENDING";
 @Column(name="operator_name",length=160) private String operatorName;
 @Column(name="actual_start") private OffsetDateTime actualStart;
 @Column(name="actual_end") private OffsetDateTime actualEnd;
 @Column(length=500) private String notes;
 public UUID getId(){return id;} public UUID getProductionOrderId(){return productionOrderId;} public void setProductionOrderId(UUID v){productionOrderId=v;} public int getSequenceNo(){return sequenceNo;} public void setSequenceNo(int v){sequenceNo=v;}
 public String getOperationCode(){return operationCode;} public void setOperationCode(String v){operationCode=v;} public String getOperationName(){return operationName;} public void setOperationName(String v){operationName=v;} public int getPlannedMinutes(){return plannedMinutes;} public void setPlannedMinutes(int v){plannedMinutes=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getOperatorName(){return operatorName;} public void setOperatorName(String v){operatorName=v;} public OffsetDateTime getActualStart(){return actualStart;} public void setActualStart(OffsetDateTime v){actualStart=v;} public OffsetDateTime getActualEnd(){return actualEnd;} public void setActualEnd(OffsetDateTime v){actualEnd=v;} public String getNotes(){return notes;} public void setNotes(String v){notes=v;}
}
