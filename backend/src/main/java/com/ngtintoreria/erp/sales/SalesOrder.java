package com.ngtintoreria.erp.sales;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "sales_orders")
public class SalesOrder {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, unique = true, length = 30) private String orderNumber;
    @Column(nullable = false, unique = true) private UUID quotationId;
    @Column(nullable = false) private UUID clientId;
    @Column(nullable = false, length = 30) private String status = "NEW";
    @Column(nullable = false, length = 20) private String priority = "NORMAL";
    private LocalDate requestedDate;
    @Column(columnDefinition = "text") private String commercialNotes;
    @Column(nullable = false, precision = 16, scale = 2) private BigDecimal total = BigDecimal.ZERO;
    @Column(nullable = false, length = 3) private String currency = "PEN";
    @Column(nullable = false, length = 160) private String createdBy;
    @Column(nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(nullable = false) private OffsetDateTime updatedAt;
    private OffsetDateTime confirmedAt;
    @PrePersist void pre(){createdAt=updatedAt=OffsetDateTime.now();} @PreUpdate void upd(){updatedAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public String getOrderNumber(){return orderNumber;} public void setOrderNumber(String v){orderNumber=v;} public UUID getQuotationId(){return quotationId;} public void setQuotationId(UUID v){quotationId=v;}
    public UUID getClientId(){return clientId;} public void setClientId(UUID v){clientId=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getPriority(){return priority;} public void setPriority(String v){priority=v;}
    public LocalDate getRequestedDate(){return requestedDate;} public void setRequestedDate(LocalDate v){requestedDate=v;} public String getCommercialNotes(){return commercialNotes;} public void setCommercialNotes(String v){commercialNotes=v;}
    public BigDecimal getTotal(){return total;} public void setTotal(BigDecimal v){total=v;} public String getCurrency(){return currency;} public void setCurrency(String v){currency=v;} public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
    public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;} public OffsetDateTime getConfirmedAt(){return confirmedAt;} public void setConfirmedAt(OffsetDateTime v){confirmedAt=v;}
}
