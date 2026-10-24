package com.ngtintoreria.erp.crm;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional; import java.util.UUID;
public interface ClientRepository extends JpaRepository<Client,UUID>{Optional<Client> findByDocumentNumber(String documentNumber);}
