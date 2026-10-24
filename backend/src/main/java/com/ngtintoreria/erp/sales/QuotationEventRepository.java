package com.ngtintoreria.erp.sales;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface QuotationEventRepository extends JpaRepository<QuotationEvent,UUID>{List<QuotationEvent> findByQuotationIdOrderByCreatedAt(UUID quotationId);}
