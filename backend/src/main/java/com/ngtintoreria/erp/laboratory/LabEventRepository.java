package com.ngtintoreria.erp.laboratory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface LabEventRepository extends JpaRepository<LabEvent,UUID>{
 List<LabEvent> findByLabRequestIdOrderByCreatedAt(UUID labRequestId);
}
