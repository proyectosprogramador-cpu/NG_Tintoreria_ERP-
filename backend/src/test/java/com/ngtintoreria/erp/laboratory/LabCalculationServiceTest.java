package com.ngtintoreria.erp.laboratory;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class LabCalculationServiceTest {
 private final LabCalculationService service=new LabCalculationService();

 @Test void calculatesBathAndGramsPerLiter(){
  BigDecimal bath=service.bathVolume(new BigDecimal("500"),new BigDecimal("8"));
  assertEquals(new BigDecimal("4000.000"),bath);
  assertEquals(new BigDecimal("240.000000"),service.componentKg(new BigDecimal("500"),bath,new BigDecimal("60"),"g/L"));
 }

 @Test void calculatesPercentageOnWeightOfFiber(){
  BigDecimal bath=service.bathVolume(new BigDecimal("500"),new BigDecimal("8"));
  assertEquals(new BigDecimal("16.000000"),service.componentKg(new BigDecimal("500"),bath,new BigDecimal("3.2"),"% OWF"));
 }

 @Test void rejectsInvalidInputs(){
  assertThrows(IllegalArgumentException.class,()->service.bathVolume(BigDecimal.ZERO,new BigDecimal("8")));
  assertThrows(IllegalArgumentException.class,()->service.componentKg(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,"kg"));
 }
}
