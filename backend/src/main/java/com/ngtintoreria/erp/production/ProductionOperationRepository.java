package com.ngtintoreria.erp.production;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ProductionOperationRepository extends JpaRepository<ProductionOperation,UUID>{
 List<ProductionOperation> findByProductionOrderIdOrderBySequenceNo(UUID productionOrderId);
 void deleteByProductionOrderId(UUID productionOrderId);
}
