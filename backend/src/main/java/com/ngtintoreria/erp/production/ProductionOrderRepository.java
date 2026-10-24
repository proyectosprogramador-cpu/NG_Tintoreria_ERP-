package com.ngtintoreria.erp.production;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface ProductionOrderRepository extends JpaRepository<ProductionOrder,UUID>{
 @Query(value="select nextval('production_order_number_seq')",nativeQuery=true) Long nextNumber();
 List<ProductionOrder> findByMachineIdOrderByScheduledStart(UUID machineId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from ProductionOrder p where p.id=:id")
 Optional<ProductionOrder> findLockedById(@Param("id") UUID id);
}
