package com.ngtintoreria.erp.masterdata;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface MachineRepository extends JpaRepository<Machine,UUID>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select m from Machine m where m.id=:id")
 Optional<Machine> findLockedById(@Param("id") UUID id);
}
