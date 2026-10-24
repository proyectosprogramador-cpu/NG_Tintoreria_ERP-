package com.ngtintoreria.erp.sales;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.*;
public interface SalesOrderRepository extends JpaRepository<SalesOrder,UUID>{
    Optional<SalesOrder> findByQuotationId(UUID quotationId);
    @Query(value="select nextval('sales_order_number_seq')",nativeQuery=true) Long nextNumber();
}
