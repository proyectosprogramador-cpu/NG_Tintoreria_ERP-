package com.ngtintoreria.erp.laboratory;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="lab_events")
public class LabEvent {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="lab_request_id",nullable=false) private UUID labRequestId;
 @Column(name="event_type",nullable=false,length=40) private String eventType;
 @Column(name="from_status",length=30) private String fromStatus;
 @Column(name="to_status",length=30) private String toStatus;
 @Column(length=500) private String comment;
 @Column(nullable=false,length=160) private String actor;
 @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
 @PrePersist void pre(){createdAt=OffsetDateTime.now();}
 public UUID getId(){return id;} public UUID getLabRequestId(){return labRequestId;} public void setLabRequestId(UUID v){labRequestId=v;} public String getEventType(){return eventType;} public void setEventType(String v){eventType=v;}
 public String getFromStatus(){return fromStatus;} public void setFromStatus(String v){fromStatus=v;} public String getToStatus(){return toStatus;} public void setToStatus(String v){toStatus=v;}
 public String getComment(){return comment;} public void setComment(String v){comment=v;} public String getActor(){return actor;} public void setActor(String v){actor=v;} public OffsetDateTime getCreatedAt(){return createdAt;}
}
