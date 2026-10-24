package com.ngtintoreria.erp.company;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="company_profile")
public class CompanyProfile {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(nullable=false,length=180) private String tradeName="Tintorería Neul Guzmán";
 @Column(length=180) private String legalName; @Column(length=20) private String ruc; @Column(length=250) private String fiscalAddress;
 @Column(length=80) private String phone; @Column(length=160) private String email; @Column(length=500) private String logoUrl;
 @Column(length=255) private String logoStorageName; @Column(length=80) private String logoContentType; @Column(length=255) private String logoOriginalName; private OffsetDateTime logoUpdatedAt;
 @Column(nullable=false,length=3) private String baseCurrency="PEN"; @Column(nullable=false) private double igvRate=18.0;
 @Column(nullable=false) private OffsetDateTime updatedAt;
 @PrePersist @PreUpdate void touch(){updatedAt=OffsetDateTime.now();}
 public UUID getId(){return id;} public String getTradeName(){return tradeName;} public void setTradeName(String v){tradeName=v;} public String getLegalName(){return legalName;} public void setLegalName(String v){legalName=v;} public String getRuc(){return ruc;} public void setRuc(String v){ruc=v;} public String getFiscalAddress(){return fiscalAddress;} public void setFiscalAddress(String v){fiscalAddress=v;} public String getPhone(){return phone;} public void setPhone(String v){phone=v;} public String getEmail(){return email;} public void setEmail(String v){email=v;} public String getLogoUrl(){return logoUrl;} public void setLogoUrl(String v){logoUrl=v;} public String getLogoStorageName(){return logoStorageName;} public void setLogoStorageName(String v){logoStorageName=v;} public String getLogoContentType(){return logoContentType;} public void setLogoContentType(String v){logoContentType=v;} public String getLogoOriginalName(){return logoOriginalName;} public void setLogoOriginalName(String v){logoOriginalName=v;} public OffsetDateTime getLogoUpdatedAt(){return logoUpdatedAt;} public void setLogoUpdatedAt(OffsetDateTime v){logoUpdatedAt=v;} public String getBaseCurrency(){return baseCurrency;} public void setBaseCurrency(String v){baseCurrency=v;} public double getIgvRate(){return igvRate;} public void setIgvRate(double v){igvRate=v;} public OffsetDateTime getUpdatedAt(){return updatedAt;}
}
