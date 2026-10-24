package com.ngtintoreria.erp.sales;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "quotation_deliveries")
public class QuotationDelivery {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private UUID quotationId;
    @Column(nullable = false, length = 20) private String channel;
    @Column(nullable = false, length = 180) private String destination;
    @Column(length = 500) private String detail;
    @Column(nullable = false, length = 160) private String sentBy;
    @Column(nullable = false) private OffsetDateTime createdAt;
    @PrePersist void pre(){createdAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public UUID getQuotationId(){return quotationId;} public void setQuotationId(UUID v){quotationId=v;} public String getChannel(){return channel;} public void setChannel(String v){channel=v;}
    public String getDestination(){return destination;} public void setDestination(String v){destination=v;} public String getDetail(){return detail;} public void setDetail(String v){detail=v;} public String getSentBy(){return sentBy;} public void setSentBy(String v){sentBy=v;} public OffsetDateTime getCreatedAt(){return createdAt;}
}
