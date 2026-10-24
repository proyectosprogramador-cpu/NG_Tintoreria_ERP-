package com.ngtintoreria.erp.sales;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.UUID;
public interface QuotationRepository extends JpaRepository<Quotation,UUID>{
    @Query(value="select nextval('quotation_number_seq')",nativeQuery=true) Long nextNumber();
}
