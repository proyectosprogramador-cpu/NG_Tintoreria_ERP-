package com.ngtintoreria.erp.production;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ProductionCancellationRequestRepository extends JpaRepository<ProductionCancellationRequest,UUID> {
 Optional<ProductionCancellationRequest> findByProductionOrderIdAndStatus(UUID productionOrderId,String status);
 Optional<ProductionCancellationRequest> findFirstByProductionOrderIdOrderByRequestedAtDesc(UUID productionOrderId);
}
