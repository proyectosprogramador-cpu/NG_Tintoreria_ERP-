package com.ngtintoreria.erp.sales;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SalesOrderEventRepository extends JpaRepository<SalesOrderEvent,UUID>{List<SalesOrderEvent> findBySalesOrderIdOrderByCreatedAt(UUID salesOrderId);}
