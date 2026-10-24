package com.ngtintoreria.erp.production;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ProductionPlanningServiceTest {
 private final ProductionPlanningService service=new ProductionPlanningService();

 @Test void validatesMachineCapacity(){
  assertDoesNotThrow(()->service.validateCapacity(new BigDecimal("250"),new BigDecimal("100"),new BigDecimal("500")));
  assertThrows(IllegalArgumentException.class,()->service.validateCapacity(new BigDecimal("99.999"),new BigDecimal("100"),new BigDecimal("500")));
  assertThrows(IllegalArgumentException.class,()->service.validateCapacity(new BigDecimal("500.001"),new BigDecimal("100"),new BigDecimal("500")));
 }

 @Test void calculatesScheduleAndDetectsOnlyRealOverlap(){
  OffsetDateTime start=OffsetDateTime.parse("2026-09-19T08:00:00-05:00");
  OffsetDateTime end=service.scheduledEnd(start,30,180,20);
  assertEquals(OffsetDateTime.parse("2026-09-19T11:50:00-05:00"),end);
  assertTrue(service.overlaps(start,end,OffsetDateTime.parse("2026-09-19T11:00:00-05:00"),OffsetDateTime.parse("2026-09-19T12:00:00-05:00")));
  assertFalse(service.overlaps(start,end,end,OffsetDateTime.parse("2026-09-19T13:00:00-05:00")));
 }

 @Test void calculatesBathAndBothRecipeUnits(){
  BigDecimal weight=new BigDecimal("250"),bath=service.bathVolume(weight,new BigDecimal("8"));
  assertEquals(new BigDecimal("2000.000"),bath);
  assertEquals(new BigDecimal("3.000000"),service.materialQuantity(weight,bath,new BigDecimal("1.5"),"G_L"));
  assertEquals(new BigDecimal("5.000000"),service.materialQuantity(weight,bath,new BigDecimal("2"),"PCT_OWF"));
  assertThrows(IllegalArgumentException.class,()->service.materialQuantity(weight,bath,BigDecimal.ONE,"UNKNOWN"));
 }
}
