package com.ngtintoreria.erp.masterdata;
import jakarta.persistence.*;
@Entity @Table(name="measurement_units",uniqueConstraints=@UniqueConstraint(columnNames="code"))
public class MeasurementUnit extends AbstractMaster { @Column(length=20) private String symbol; @Column(length=60) private String dimension; public String getSymbol(){return symbol;} public void setSymbol(String v){symbol=v;} public String getDimension(){return dimension;} public void setDimension(String v){dimension=v;} }
