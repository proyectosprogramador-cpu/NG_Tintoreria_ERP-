package com.ngtintoreria.erp.production;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.OffsetDateTime;

@Service
public class ProductionPlanningService {
 public void validateCapacity(BigDecimal weight,BigDecimal minimum,BigDecimal maximum){
  if(weight==null||weight.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("El peso del lote debe ser mayor que cero");
  if(minimum!=null&&minimum.compareTo(BigDecimal.ZERO)>0&&weight.compareTo(minimum)<0)
   throw new IllegalArgumentException("El lote está por debajo de la capacidad mínima de la máquina ("+minimum+" kg)");
  if(maximum!=null&&maximum.compareTo(BigDecimal.ZERO)>0&&weight.compareTo(maximum)>0)
   throw new IllegalArgumentException("El lote supera la capacidad máxima de la máquina ("+maximum+" kg)");
 }
 public OffsetDateTime scheduledEnd(OffsetDateTime start,int setupMinutes,int operationMinutes,int cleaningMinutes){
  if(start==null)throw new IllegalArgumentException("Indique la fecha y hora programada");
  if(setupMinutes<0||cleaningMinutes<0||operationMinutes<=0)throw new IllegalArgumentException("Los tiempos programados no son válidos");
  return start.plusMinutes((long)setupMinutes+operationMinutes+cleaningMinutes);
 }
 public boolean overlaps(OffsetDateTime start,OffsetDateTime end,OffsetDateTime otherStart,OffsetDateTime otherEnd){
  return start.isBefore(otherEnd)&&end.isAfter(otherStart);
 }
 public BigDecimal bathVolume(BigDecimal weight,BigDecimal bathRatio){
  if(bathRatio==null||bathRatio.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("La receta liberada no tiene relación de baño válida");
  return weight.multiply(bathRatio).setScale(3,RoundingMode.HALF_UP);
 }
 public BigDecimal materialQuantity(BigDecimal weight,BigDecimal bathLiters,BigDecimal dosage,String unit){
  if(dosage==null||dosage.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("La receta contiene una dosificación no válida");
  if("G_L".equals(unit))return bathLiters.multiply(dosage).divide(new BigDecimal("1000"),6,RoundingMode.HALF_UP);
  if("PCT_OWF".equals(unit))return weight.multiply(dosage).divide(new BigDecimal("100"),6,RoundingMode.HALF_UP);
  throw new IllegalArgumentException("Unidad de receta no compatible: "+unit);
 }
}
