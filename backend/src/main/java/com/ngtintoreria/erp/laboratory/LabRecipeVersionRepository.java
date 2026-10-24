package com.ngtintoreria.erp.laboratory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface LabRecipeVersionRepository extends JpaRepository<LabRecipeVersion,UUID>{
 List<LabRecipeVersion> findByLabRequestIdOrderByAlternativeCodeAscVersionNoDesc(UUID labRequestId);
 Optional<LabRecipeVersion> findFirstByLabRequestIdAndAlternativeCodeOrderByVersionNoDesc(UUID labRequestId,String alternativeCode);
}
