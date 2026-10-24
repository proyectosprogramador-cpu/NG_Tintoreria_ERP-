package com.ngtintoreria.erp.production;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
@Entity @Table(name="production_order_rolls",uniqueConstraints=@UniqueConstraint(columnNames={"production_order_id","reception_roll_id"}))
public class ProductionOrderRoll {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="production_order_id",nullable=false) private UUID productionOrderId;
 @Column(name="reception_roll_id",nullable=false) private UUID receptionRollId;
 @Column(name="sequence_no",nullable=false) private int sequenceNo;
 @Column(name="net_kg",nullable=false,precision=14,scale=3) private BigDecimal netKg=BigDecimal.ZERO;
 @Column(nullable=false,precision=14,scale=3) private BigDecimal meters=BigDecimal.ZERO;
 public UUID getId(){return id;} public UUID getProductionOrderId(){return productionOrderId;} public void setProductionOrderId(UUID v){productionOrderId=v;} public UUID getReceptionRollId(){return receptionRollId;} public void setReceptionRollId(UUID v){receptionRollId=v;}
 public int getSequenceNo(){return sequenceNo;} public void setSequenceNo(int v){sequenceNo=v;} public BigDecimal getNetKg(){return netKg;} public void setNetKg(BigDecimal v){netKg=v;} public BigDecimal getMeters(){return meters;} public void setMeters(BigDecimal v){meters=v;}
}
