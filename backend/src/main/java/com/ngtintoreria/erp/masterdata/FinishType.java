package com.ngtintoreria.erp.masterdata;
import jakarta.persistence.*; import java.math.BigDecimal;
@Entity @Table(name="finish_types",uniqueConstraints=@UniqueConstraint(columnNames="code"))
public class FinishType extends AbstractMaster { @Column(length=20) private String billingUnit="kg"; @Column(precision=14,scale=4) private BigDecimal additionalPrice=BigDecimal.ZERO; public String getBillingUnit(){return billingUnit;} public void setBillingUnit(String v){billingUnit=v;} public BigDecimal getAdditionalPrice(){return additionalPrice;} public void setAdditionalPrice(BigDecimal v){additionalPrice=v;} }
