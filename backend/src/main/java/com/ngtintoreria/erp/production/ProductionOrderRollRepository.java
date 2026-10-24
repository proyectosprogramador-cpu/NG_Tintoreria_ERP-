package com.ngtintoreria.erp.production;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ProductionOrderRollRepository extends JpaRepository<ProductionOrderRoll,UUID>{
 List<ProductionOrderRoll> findByProductionOrderIdOrderBySequenceNo(UUID productionOrderId);
 List<ProductionOrderRoll> findByReceptionRollId(UUID receptionRollId);
 void deleteByProductionOrderId(UUID productionOrderId);
}
