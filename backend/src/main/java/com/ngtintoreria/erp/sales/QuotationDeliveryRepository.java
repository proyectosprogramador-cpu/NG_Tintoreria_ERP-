package com.ngtintoreria.erp.sales;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface QuotationDeliveryRepository extends JpaRepository<QuotationDelivery,UUID>{List<QuotationDelivery> findByQuotationIdOrderByCreatedAt(UUID quotationId);}
