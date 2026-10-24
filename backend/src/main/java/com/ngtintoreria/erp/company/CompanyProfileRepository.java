package com.ngtintoreria.erp.company;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface CompanyProfileRepository extends JpaRepository<CompanyProfile, UUID>{}
