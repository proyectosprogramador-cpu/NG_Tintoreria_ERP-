package com.ngtintoreria.erp.production;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ProductionMaterialRequirementRepository extends JpaRepository<ProductionMaterialRequirement,UUID>{
 List<ProductionMaterialRequirement> findByProductionOrderIdOrderByLineNo(UUID productionOrderId);
 void deleteByProductionOrderId(UUID productionOrderId);
}
