package com.ngtintoreria.erp.receiving;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="reception_attachments")
public class ReceptionAttachment {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="reception_id",nullable=false) private UUID receptionId;
    @Column(name="roll_id") private UUID rollId;
    @Column(name="original_name",nullable=false,length=255) private String originalName;
    @Column(name="content_type",nullable=false,length=100) private String contentType;
    @Column(name="size_bytes",nullable=false) private long sizeBytes;
    @Column(name="storage_name",nullable=false,unique=true,length=255) private String storageName;
    @Column(name="uploaded_by",nullable=false,length=160) private String uploadedBy;
    @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
    @PrePersist void pre(){createdAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public UUID getReceptionId(){return receptionId;} public void setReceptionId(UUID v){receptionId=v;} public UUID getRollId(){return rollId;} public void setRollId(UUID v){rollId=v;}
    public String getOriginalName(){return originalName;} public void setOriginalName(String v){originalName=v;} public String getContentType(){return contentType;} public void setContentType(String v){contentType=v;} public long getSizeBytes(){return sizeBytes;} public void setSizeBytes(long v){sizeBytes=v;}
    public String getStorageName(){return storageName;} public void setStorageName(String v){storageName=v;} public String getUploadedBy(){return uploadedBy;} public void setUploadedBy(String v){uploadedBy=v;} public OffsetDateTime getCreatedAt(){return createdAt;}
}
