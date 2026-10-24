package com.ngtintoreria.erp.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class MaintenanceAvailabilityService {
    private final JdbcTemplate jdbc;
    public MaintenanceAvailabilityService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public void assertNoMaintenanceConflict(UUID machineId,OffsetDateTime start,OffsetDateTime end){
        lockMachine(machineId);
        Integer count=jdbc.queryForObject("""
            select count(*) from maintenance_work_orders
             where machine_id=? and status in ('PLANNED','IN_PROGRESS')
               and scheduled_start < ? and scheduled_end > ?
            """,Integer.class,machineId,end,start);
        if(count!=null&&count>0){
            List<Map<String,Object>> rows=jdbc.queryForList("""
                select maintenance_number,title,scheduled_start,scheduled_end,status
                  from maintenance_work_orders
                 where machine_id=? and status in ('PLANNED','IN_PROGRESS')
                   and scheduled_start < ? and scheduled_end > ?
                 order by scheduled_start limit 1
                """,machineId,end,start);
            Map<String,Object> x=rows.isEmpty()?Map.of():rows.get(0);
            throw new IllegalArgumentException("La máquina tiene mantenimiento "+x.getOrDefault("maintenance_number","")+" programado/en curso entre "+x.getOrDefault("scheduled_start","")+" y "+x.getOrDefault("scheduled_end",""));
        }
    }

    public void assertNoProductionConflict(UUID machineId,OffsetDateTime start,OffsetDateTime end,UUID excludeProductionId){
        lockMachine(machineId);
        String sql="""
            select production_number,scheduled_start,scheduled_end,status from production_orders
             where machine_id=? and status in ('PLANNED','IN_PROCESS','PAUSED','REPROCESS_REQUIRED')
               and scheduled_start < ? and scheduled_end > ?
            """+(excludeProductionId==null?"":" and id<>?")+" order by scheduled_start limit 1";
        Object[] args=excludeProductionId==null?new Object[]{machineId,end,start}:new Object[]{machineId,end,start,excludeProductionId};
        List<Map<String,Object>> rows=jdbc.queryForList(sql,args);
        if(!rows.isEmpty()){
            Map<String,Object> x=rows.get(0);
            throw new IllegalArgumentException("La máquina ya está comprometida por "+x.get("production_number")+" entre "+x.get("scheduled_start")+" y "+x.get("scheduled_end"));
        }
    }

    public void assertMachineAvailableNow(UUID machineId){
        lockMachine(machineId);
        List<Map<String,Object>> rows=jdbc.queryForList("select active,coalesce(operational_status,'AVAILABLE') operational_status from machines where id=?",machineId);
        if(rows.isEmpty())throw new IllegalArgumentException("La máquina no existe");
        Map<String,Object> m=rows.get(0);
        if(Boolean.FALSE.equals(m.get("active")))throw new IllegalArgumentException("La máquina está inactiva");
        String status=String.valueOf(m.get("operational_status"));
        if(!"AVAILABLE".equalsIgnoreCase(status))throw new IllegalArgumentException("La máquina no está disponible. Estado actual: "+status);
    }

    public void postProductionMeterHours(UUID productionOrderId,UUID machineId){
        BigDecimal hours=jdbc.queryForObject("""
            select coalesce(sum(extract(epoch from (actual_end-actual_start))/3600.0),0)::numeric
              from production_operations
             where production_order_id=? and actual_start is not null and actual_end is not null
            """,BigDecimal.class,productionOrderId);
        if(hours==null||hours.compareTo(BigDecimal.ZERO)<=0)return;
        lockMachine(machineId);
        int marked=jdbc.update("update production_orders set machine_hours_posted=true where id=? and machine_hours_posted=false",productionOrderId);
        if(marked==0)return;
        jdbc.update("update machines set current_meter_hours=coalesce(current_meter_hours,0)+?,updated_at=now() where id=?",hours,machineId);
    }

    private void lockMachine(UUID machineId){
        List<Map<String,Object>> rows=jdbc.queryForList("select id from machines where id=? for update",machineId);
        if(rows.isEmpty())throw new IllegalArgumentException("La máquina no existe");
    }
}
