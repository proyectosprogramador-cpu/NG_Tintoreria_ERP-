package com.ngtintoreria.erp.production;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ProductionEventRepository extends JpaRepository<ProductionEvent,UUID>{
 List<ProductionEvent> findByProductionOrderIdOrderByCreatedAt(UUID productionOrderId);
}
