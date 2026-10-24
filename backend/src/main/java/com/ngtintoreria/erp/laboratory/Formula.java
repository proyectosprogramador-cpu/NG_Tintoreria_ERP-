package com.ngtintoreria.erp.laboratory;
import jakarta.persistence.*; import java.time.OffsetDateTime; import java.util.UUID;
@Entity @Table(name="formulas")
public class Formula {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(nullable=false,unique=true,length=60) private String code; @Column(nullable=false,length=180) private String name;
 private UUID clientId; @Column(nullable=false) private UUID fabricId; @Column(nullable=false) private UUID colorId; @Column(nullable=false) private UUID processId; private UUID finishId;
 @Column(nullable=false) private boolean active=true; @Column(nullable=false) private int currentVersion=1;
 @Column(nullable=false,updatable=false) private OffsetDateTime createdAt; @Column(nullable=false) private OffsetDateTime updatedAt;
 @PrePersist void pre(){createdAt=updatedAt=OffsetDateTime.now();} @PreUpdate void upd(){updatedAt=OffsetDateTime.now();}
 public UUID getId(){return id;} public String getCode(){return code;} public void setCode(String v){code=v;} public String getName(){return name;} public void setName(String v){name=v;} public UUID getClientId(){return clientId;} public void setClientId(UUID v){clientId=v;} public UUID getFabricId(){return fabricId;} public void setFabricId(UUID v){fabricId=v;} public UUID getColorId(){return colorId;} public void setColorId(UUID v){colorId=v;} public UUID getProcessId(){return processId;} public void setProcessId(UUID v){processId=v;} public UUID getFinishId(){return finishId;} public void setFinishId(UUID v){finishId=v;} public boolean isActive(){return active;} public void setActive(boolean v){active=v;} public int getCurrentVersion(){return currentVersion;} public void setCurrentVersion(int v){currentVersion=v;} public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;}
}
