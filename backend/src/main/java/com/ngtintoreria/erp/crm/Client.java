package com.ngtintoreria.erp.crm;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="clients")
public class Client {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(nullable=false,unique=true,length=30) private String documentNumber;
 @Column(nullable=false,length=180) private String name;
 @Column(length=180) private String contactName; @Column(length=80) private String whatsapp; @Column(length=160) private String email; @Column(length=250) private String address;
 @Column(nullable=false) private int creditDays=0; @Column(nullable=false) private boolean active=true;
 @Column(nullable=false,updatable=false) private OffsetDateTime createdAt; @Column(nullable=false) private OffsetDateTime updatedAt;
 @PrePersist void pre(){createdAt=updatedAt=OffsetDateTime.now();} @PreUpdate void upd(){updatedAt=OffsetDateTime.now();}
 public UUID getId(){return id;} public String getDocumentNumber(){return documentNumber;} public void setDocumentNumber(String v){documentNumber=v;} public String getName(){return name;} public void setName(String v){name=v;} public String getContactName(){return contactName;} public void setContactName(String v){contactName=v;} public String getWhatsapp(){return whatsapp;} public void setWhatsapp(String v){whatsapp=v;} public String getEmail(){return email;} public void setEmail(String v){email=v;} public String getAddress(){return address;} public void setAddress(String v){address=v;} public int getCreditDays(){return creditDays;} public void setCreditDays(int v){creditDays=v;} public boolean isActive(){return active;} public void setActive(boolean v){active=v;} public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;}
}
