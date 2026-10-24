package com.ngtintoreria.erp.masterdata;
import jakarta.persistence.*;
@Entity @Table(name="dye_colors",uniqueConstraints=@UniqueConstraint(columnNames="code"))
public class DyeColor extends AbstractMaster { @Column(length=80) private String family; @Column(length=80) private String pantone; private Double surchargePct=0.0; public String getFamily(){return family;} public void setFamily(String v){family=v;} public String getPantone(){return pantone;} public void setPantone(String v){pantone=v;} public Double getSurchargePct(){return surchargePct;} public void setSurchargePct(Double v){surchargePct=v;} }
