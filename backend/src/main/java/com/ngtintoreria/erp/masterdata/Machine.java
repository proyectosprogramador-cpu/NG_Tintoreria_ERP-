package com.ngtintoreria.erp.masterdata;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name="machines",uniqueConstraints=@UniqueConstraint(columnNames="code"))
public class Machine extends AbstractMaster {
 @Column(precision=14,scale=3) private BigDecimal minCapacityKg=BigDecimal.ZERO;
 @Column(precision=14,scale=3) private BigDecimal maxCapacityKg=BigDecimal.ZERO;
 @Column(columnDefinition="text") private String compatibleProcesses;
 @Column(length=30) private String operationalStatus="AVAILABLE";
 @Column(name="cost_per_hour",precision=18,scale=6) private BigDecimal costPerHour;
 @Column(length=120) private String brand;
 @Column(length=120) private String model;
 @Column(name="serial_number",length=120) private String serialNumber;
 @Column(length=160) private String location;
 @Column(name="commissioning_date") private LocalDate commissioningDate;
 @Column(name="current_meter_hours",precision=16,scale=2) private BigDecimal currentMeterHours=BigDecimal.ZERO;
 @Column(name="maintenance_interval_hours",precision=16,scale=2) private BigDecimal maintenanceIntervalHours;
 @Column(name="maintenance_interval_days") private Integer maintenanceIntervalDays;
 @Column(name="last_maintenance_at") private OffsetDateTime lastMaintenanceAt;
 @Column(name="last_maintenance_meter_hours",precision=16,scale=2) private BigDecimal lastMaintenanceMeterHours;
 @Column(name="next_maintenance_at") private OffsetDateTime nextMaintenanceAt;
 @Column(name="next_maintenance_meter_hours",precision=16,scale=2) private BigDecimal nextMaintenanceMeterHours;
 public BigDecimal getMinCapacityKg(){return minCapacityKg;} public void setMinCapacityKg(BigDecimal v){minCapacityKg=v;}
 public BigDecimal getMaxCapacityKg(){return maxCapacityKg;} public void setMaxCapacityKg(BigDecimal v){maxCapacityKg=v;}
 public String getCompatibleProcesses(){return compatibleProcesses;} public void setCompatibleProcesses(String v){compatibleProcesses=v;}
 public String getOperationalStatus(){return operationalStatus;} public void setOperationalStatus(String v){operationalStatus=v;}
 public BigDecimal getCostPerHour(){return costPerHour;} public void setCostPerHour(BigDecimal v){costPerHour=v;}
 public String getBrand(){return brand;} public void setBrand(String v){brand=v;} public String getModel(){return model;} public void setModel(String v){model=v;}
 public String getSerialNumber(){return serialNumber;} public void setSerialNumber(String v){serialNumber=v;} public String getLocation(){return location;} public void setLocation(String v){location=v;}
 public LocalDate getCommissioningDate(){return commissioningDate;} public void setCommissioningDate(LocalDate v){commissioningDate=v;}
 public BigDecimal getCurrentMeterHours(){return currentMeterHours;} public void setCurrentMeterHours(BigDecimal v){currentMeterHours=v;}
 public BigDecimal getMaintenanceIntervalHours(){return maintenanceIntervalHours;} public void setMaintenanceIntervalHours(BigDecimal v){maintenanceIntervalHours=v;}
 public Integer getMaintenanceIntervalDays(){return maintenanceIntervalDays;} public void setMaintenanceIntervalDays(Integer v){maintenanceIntervalDays=v;}
 public OffsetDateTime getLastMaintenanceAt(){return lastMaintenanceAt;} public void setLastMaintenanceAt(OffsetDateTime v){lastMaintenanceAt=v;}
 public BigDecimal getLastMaintenanceMeterHours(){return lastMaintenanceMeterHours;} public void setLastMaintenanceMeterHours(BigDecimal v){lastMaintenanceMeterHours=v;}
 public OffsetDateTime getNextMaintenanceAt(){return nextMaintenanceAt;} public void setNextMaintenanceAt(OffsetDateTime v){nextMaintenanceAt=v;}
 public BigDecimal getNextMaintenanceMeterHours(){return nextMaintenanceMeterHours;} public void setNextMaintenanceMeterHours(BigDecimal v){nextMaintenanceMeterHours=v;}
}
