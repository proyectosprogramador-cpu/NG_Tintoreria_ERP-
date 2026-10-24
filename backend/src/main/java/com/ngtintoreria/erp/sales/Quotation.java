package com.ngtintoreria.erp.sales;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "quotations")
public class Quotation {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, unique = true, length = 30) private String quoteNumber;
    @Column(nullable = false) private UUID clientId;
    @Column(nullable = false, length = 30) private String status = "DRAFT";
    @Column(nullable = false, length = 3) private String currency = "PEN";
    private LocalDate validUntil;
    private LocalDate requestedDate;
    @Column(length = 180) private String paymentTerms;
    @Column(nullable = false, precision = 8, scale = 3) private BigDecimal taxRate = new BigDecimal("18");
    @Column(nullable = false, precision = 8, scale = 3) private BigDecimal discountPct = BigDecimal.ZERO;
    @Column(nullable = false, precision = 16, scale = 2) private BigDecimal subtotal = BigDecimal.ZERO;
    @Column(nullable = false, precision = 16, scale = 2) private BigDecimal discountAmount = BigDecimal.ZERO;
    @Column(nullable = false, precision = 16, scale = 2) private BigDecimal taxAmount = BigDecimal.ZERO;
    @Column(nullable = false, precision = 16, scale = 2) private BigDecimal total = BigDecimal.ZERO;
    @Column(columnDefinition = "text") private String notes;
    @Column(nullable = false) private int versionNo = 1;
    @Column(nullable = false, length = 160) private String createdBy;
    @Column(nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(nullable = false) private OffsetDateTime updatedAt;
    private OffsetDateTime sentAt;
    private OffsetDateTime approvedAt;

    @PrePersist void prePersist(){createdAt = updatedAt = OffsetDateTime.now();}
    @PreUpdate void preUpdate(){updatedAt = OffsetDateTime.now();}

    public UUID getId(){return id;} public String getQuoteNumber(){return quoteNumber;} public void setQuoteNumber(String v){quoteNumber=v;}
    public UUID getClientId(){return clientId;} public void setClientId(UUID v){clientId=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getCurrency(){return currency;} public void setCurrency(String v){currency=v;} public LocalDate getValidUntil(){return validUntil;} public void setValidUntil(LocalDate v){validUntil=v;}
    public LocalDate getRequestedDate(){return requestedDate;} public void setRequestedDate(LocalDate v){requestedDate=v;} public String getPaymentTerms(){return paymentTerms;} public void setPaymentTerms(String v){paymentTerms=v;}
    public BigDecimal getTaxRate(){return taxRate;} public void setTaxRate(BigDecimal v){taxRate=v;} public BigDecimal getDiscountPct(){return discountPct;} public void setDiscountPct(BigDecimal v){discountPct=v;}
    public BigDecimal getSubtotal(){return subtotal;} public void setSubtotal(BigDecimal v){subtotal=v;} public BigDecimal getDiscountAmount(){return discountAmount;} public void setDiscountAmount(BigDecimal v){discountAmount=v;}
    public BigDecimal getTaxAmount(){return taxAmount;} public void setTaxAmount(BigDecimal v){taxAmount=v;} public BigDecimal getTotal(){return total;} public void setTotal(BigDecimal v){total=v;}
    public String getNotes(){return notes;} public void setNotes(String v){notes=v;} public int getVersionNo(){return versionNo;} public void setVersionNo(int v){versionNo=v;}
    public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;} public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;}
    public OffsetDateTime getSentAt(){return sentAt;} public void setSentAt(OffsetDateTime v){sentAt=v;} public OffsetDateTime getApprovedAt(){return approvedAt;} public void setApprovedAt(OffsetDateTime v){approvedAt=v;}
}
