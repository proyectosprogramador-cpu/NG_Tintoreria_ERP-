package com.ngtintoreria.erp.laboratory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface ColorLabRequestRepository extends JpaRepository<ColorLabRequest,UUID>{
 @Query(value="select nextval('lab_request_number_seq')",nativeQuery=true) Long nextNumber();
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select r from ColorLabRequest r where r.id=:id")
 Optional<ColorLabRequest> findLockedById(@Param("id") UUID id);
 boolean existsBySalesOrderItemIdAndStatusIn(UUID salesOrderItemId,Collection<String> statuses);
}
