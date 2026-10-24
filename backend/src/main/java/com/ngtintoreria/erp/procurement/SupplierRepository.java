package com.ngtintoreria.erp.procurement;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.Optional; import java.util.UUID;
public interface SupplierRepository extends JpaRepository<Supplier,UUID>{Optional<Supplier> findByRuc(String ruc);}
