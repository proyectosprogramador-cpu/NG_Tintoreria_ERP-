package com.ngtintoreria.erp.receiving;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="reception_events")
public class ReceptionEvent {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="reception_id",nullable=false) private UUID receptionId;
    @Column(name="from_status",length=30) private String fromStatus;
    @Column(name="to_status",nullable=false,length=30) private String toStatus;
    @Column(name="comment",length=500) private String comment;
    @Column(name="actor",nullable=false,length=160) private String actor;
    @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
    @PrePersist void pre(){createdAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public UUID getReceptionId(){return receptionId;} public void setReceptionId(UUID v){receptionId=v;} public String getFromStatus(){return fromStatus;} public void setFromStatus(String v){fromStatus=v;}
    public String getToStatus(){return toStatus;} public void setToStatus(String v){toStatus=v;} public String getComment(){return comment;} public void setComment(String v){comment=v;} public String getActor(){return actor;} public void setActor(String v){actor=v;} public OffsetDateTime getCreatedAt(){return createdAt;}
}
