package com.ngtintoreria.erp.receiving;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.*;
public interface FabricReceptionRepository extends JpaRepository<FabricReception,UUID>{
    List<FabricReception> findBySalesOrderId(UUID salesOrderId);
    @Query(value="select nextval('reception_number_seq')",nativeQuery=true) Long nextNumber();
}
