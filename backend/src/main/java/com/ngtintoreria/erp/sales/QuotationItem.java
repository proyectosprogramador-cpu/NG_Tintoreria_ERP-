package com.ngtintoreria.erp.sales;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "quotation_items")
public class QuotationItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private UUID quotationId;
    @Column(nullable = false) private int lineNo;
    @Column(nullable = false) private UUID fabricId;
    @Column(nullable = false) private UUID colorId;
    @Column(nullable = false) private UUID processId;
    private UUID finishId;
    private UUID formulaId;
    @Column(length = 500) private String description;
    @Column(nullable = false, precision = 14, scale = 3) private BigDecimal quantity;
    @Column(nullable = false, precision = 14, scale = 3) private BigDecimal billableQuantity;
    @Column(nullable = false, length = 20) private String unit;
    @Column(nullable = false, precision = 14, scale = 4) private BigDecimal unitPrice;
    @Column(nullable = false, precision = 8, scale = 3) private BigDecimal surchargePct = BigDecimal.ZERO;
    @Column(nullable = false, precision = 8, scale = 3) private BigDecimal discountPct = BigDecimal.ZERO;
    @Column(nullable = false, precision = 16, scale = 2) private BigDecimal lineSubtotal;
    @Column(nullable = false, precision = 16, scale = 2) private BigDecimal lineTotal;
    @Column(length = 180) private String priceSource;

    public UUID getId(){return id;} public UUID getQuotationId(){return quotationId;} public void setQuotationId(UUID v){quotationId=v;} public int getLineNo(){return lineNo;} public void setLineNo(int v){lineNo=v;}
    public UUID getFabricId(){return fabricId;} public void setFabricId(UUID v){fabricId=v;} public UUID getColorId(){return colorId;} public void setColorId(UUID v){colorId=v;} public UUID getProcessId(){return processId;} public void setProcessId(UUID v){processId=v;}
    public UUID getFinishId(){return finishId;} public void setFinishId(UUID v){finishId=v;} public UUID getFormulaId(){return formulaId;} public void setFormulaId(UUID v){formulaId=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public BigDecimal getQuantity(){return quantity;} public void setQuantity(BigDecimal v){quantity=v;} public BigDecimal getBillableQuantity(){return billableQuantity;} public void setBillableQuantity(BigDecimal v){billableQuantity=v;}
    public String getUnit(){return unit;} public void setUnit(String v){unit=v;} public BigDecimal getUnitPrice(){return unitPrice;} public void setUnitPrice(BigDecimal v){unitPrice=v;}
    public BigDecimal getSurchargePct(){return surchargePct;} public void setSurchargePct(BigDecimal v){surchargePct=v;} public BigDecimal getDiscountPct(){return discountPct;} public void setDiscountPct(BigDecimal v){discountPct=v;}
    public BigDecimal getLineSubtotal(){return lineSubtotal;} public void setLineSubtotal(BigDecimal v){lineSubtotal=v;} public BigDecimal getLineTotal(){return lineTotal;} public void setLineTotal(BigDecimal v){lineTotal=v;}
    public String getPriceSource(){return priceSource;} public void setPriceSource(String v){priceSource=v;}
}
