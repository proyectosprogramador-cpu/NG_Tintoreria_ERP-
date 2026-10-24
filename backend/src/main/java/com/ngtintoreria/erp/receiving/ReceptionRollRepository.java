package com.ngtintoreria.erp.receiving;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface ReceptionRollRepository extends JpaRepository<ReceptionRoll,UUID>{
    List<ReceptionRoll> findByReceptionIdOrderByLineNo(UUID receptionId);
    List<ReceptionRoll> findBySalesOrderItemIdOrderByCreatedAt(UUID salesOrderItemId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from ReceptionRoll r where r.id=:id")
    Optional<ReceptionRoll> findLockedById(@Param("id") UUID id);
    @Query(value="select nextval('reception_roll_number_seq')",nativeQuery=true) Long nextNumber();
}
