package com.ngtintoreria.erp.sales;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface QuotationItemRepository extends JpaRepository<QuotationItem,UUID>{
    List<QuotationItem> findByQuotationIdOrderByLineNo(UUID quotationId);
    void deleteByQuotationId(UUID quotationId);
}
