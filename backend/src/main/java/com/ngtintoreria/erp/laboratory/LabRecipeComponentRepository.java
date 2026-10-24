package com.ngtintoreria.erp.laboratory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface LabRecipeComponentRepository extends JpaRepository<LabRecipeComponent,UUID>{
 List<LabRecipeComponent> findByRecipeVersionIdOrderByLineNo(UUID recipeVersionId);
}
