package com.ngtintoreria.erp.masterdata;
import jakarta.persistence.*;
@Entity @Table(name="fabrics",uniqueConstraints=@UniqueConstraint(columnNames="code"))
public class Fabric extends AbstractMaster { @Column(length=180) private String composition; private Double gsm; private Double widthCm; @Column(length=20) private String defaultUnit="kg"; public String getComposition(){return composition;} public void setComposition(String v){composition=v;} public Double getGsm(){return gsm;} public void setGsm(Double v){gsm=v;} public Double getWidthCm(){return widthCm;} public void setWidthCm(Double v){widthCm=v;} public String getDefaultUnit(){return defaultUnit;} public void setDefaultUnit(String v){defaultUnit=v;} }
