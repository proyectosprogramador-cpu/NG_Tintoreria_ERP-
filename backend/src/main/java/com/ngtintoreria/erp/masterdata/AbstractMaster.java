package com.ngtintoreria.erp.masterdata;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@MappedSuperclass
public abstract class AbstractMaster {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(nullable=false,unique=false,length=40) private String code;
 @Column(nullable=false,length=180) private String name;
 @Column(nullable=false) private boolean active=true;
 @Column(nullable=false,updatable=false) private OffsetDateTime createdAt;
 @Column(nullable=false) private OffsetDateTime updatedAt;
 @PrePersist void pre(){createdAt=updatedAt=OffsetDateTime.now();} @PreUpdate void upd(){updatedAt=OffsetDateTime.now();}
 public UUID getId(){return id;} public String getCode(){return code;} public void setCode(String v){code=v;} public String getName(){return name;} public void setName(String v){name=v;} public boolean isActive(){return active;} public void setActive(boolean v){active=v;} public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;}
}
