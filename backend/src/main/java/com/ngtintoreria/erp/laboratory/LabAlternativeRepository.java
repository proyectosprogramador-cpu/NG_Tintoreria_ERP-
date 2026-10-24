package com.ngtintoreria.erp.laboratory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface LabAlternativeRepository extends JpaRepository<LabAlternative,UUID>{
 List<LabAlternative> findByLabRequestIdOrderByAlternativeCode(UUID labRequestId);
 Optional<LabAlternative> findByLabRequestIdAndAlternativeCode(UUID labRequestId,String alternativeCode);
}
