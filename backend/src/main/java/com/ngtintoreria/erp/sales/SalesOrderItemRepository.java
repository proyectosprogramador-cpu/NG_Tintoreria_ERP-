package com.ngtintoreria.erp.sales;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SalesOrderItemRepository extends JpaRepository<SalesOrderItem,UUID>{List<SalesOrderItem> findBySalesOrderIdOrderByLineNo(UUID salesOrderId);}
