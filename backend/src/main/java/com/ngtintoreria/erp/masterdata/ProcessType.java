package com.ngtintoreria.erp.masterdata;
import jakarta.persistence.*; import java.math.BigDecimal;
@Entity @Table(name="process_types",uniqueConstraints=@UniqueConstraint(columnNames="code"))
public class ProcessType extends AbstractMaster { @Column(length=20) private String billingUnit="kg"; @Column(precision=14,scale=4) private BigDecimal basePrice=BigDecimal.ZERO; @Column(precision=14,scale=3) private BigDecimal minimumLot=BigDecimal.ZERO; public String getBillingUnit(){return billingUnit;} public void setBillingUnit(String v){billingUnit=v;} public BigDecimal getBasePrice(){return basePrice;} public void setBasePrice(BigDecimal v){basePrice=v;} public BigDecimal getMinimumLot(){return minimumLot;} public void setMinimumLot(BigDecimal v){minimumLot=v;} }
