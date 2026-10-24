package com.ngtintoreria.erp.procurement;
import jakarta.persistence.*;
import java.time.OffsetDateTime; import java.util.UUID;
@Entity @Table(name="suppliers")
public class Supplier {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id; @Column(nullable=false,unique=true,length=30) private String ruc; @Column(nullable=false,length=180) private String name;
 @Column(length=180) private String contactName; @Column(length=160) private String email; @Column(length=80) private String phone; @Column(length=120) private String paymentTerms; @Column(length=250) private String bankReference; @Column(length=250) private String address;
 @Column(nullable=false) private boolean active=true; @Column(nullable=false,updatable=false) private OffsetDateTime createdAt; @Column(nullable=false) private OffsetDateTime updatedAt;
 @PrePersist void pre(){createdAt=updatedAt=OffsetDateTime.now();} @PreUpdate void upd(){updatedAt=OffsetDateTime.now();}
 public UUID getId(){return id;} public String getRuc(){return ruc;} public void setRuc(String v){ruc=v;} public String getName(){return name;} public void setName(String v){name=v;} public String getContactName(){return contactName;} public void setContactName(String v){contactName=v;} public String getEmail(){return email;} public void setEmail(String v){email=v;} public String getPhone(){return phone;} public void setPhone(String v){phone=v;} public String getPaymentTerms(){return paymentTerms;} public void setPaymentTerms(String v){paymentTerms=v;} public String getBankReference(){return bankReference;} public void setBankReference(String v){bankReference=v;} public String getAddress(){return address;} public void setAddress(String v){address=v;} public boolean isActive(){return active;} public void setActive(boolean v){active=v;} public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;}
}
