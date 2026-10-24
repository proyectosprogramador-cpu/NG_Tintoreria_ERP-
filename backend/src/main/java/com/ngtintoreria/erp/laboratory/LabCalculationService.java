package com.ngtintoreria.erp.laboratory;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class LabCalculationService {
 public BigDecimal bathVolume(BigDecimal actualWeightKg,BigDecimal bathRatio){
  positive(actualWeightKg,"El peso real debe ser mayor que cero");
  positive(bathRatio,"La relación de baño debe ser mayor que cero");
  return actualWeightKg.multiply(bathRatio).setScale(3,RoundingMode.HALF_UP);
 }
 public BigDecimal componentKg(BigDecimal actualWeightKg,BigDecimal bathVolumeL,BigDecimal dosage,String unit){
  positive(dosage,"La dosificación debe ser mayor que cero");
  String normalized=normalizeUnit(unit);
  BigDecimal value="G_L".equals(normalized)
    ? bathVolumeL.multiply(dosage).divide(new BigDecimal("1000"),6,RoundingMode.HALF_UP)
    : actualWeightKg.multiply(dosage).divide(new BigDecimal("100"),6,RoundingMode.HALF_UP);
  return value.setScale(6,RoundingMode.HALF_UP);
 }
 public String normalizeUnit(String unit){
  String v=unit==null?"":unit.trim().toUpperCase(Locale.ROOT).replace("%","PCT_").replace(" ","");
  if(Set.of("G/L","GL","G_L").contains(v))return "G_L";
  if(Set.of("PCT_OWF","PCTOWF","OWF").contains(v))return "PCT_OWF";
  throw new IllegalArgumentException("Unidad de dosificación no válida; use g/L o % OWF");
 }
 private void positive(BigDecimal value,String message){if(value==null||value.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException(message);}
}
