package com.ngtintoreria.erp.receiving;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ReceptionEventRepository extends JpaRepository<ReceptionEvent,UUID>{List<ReceptionEvent> findByReceptionIdOrderByCreatedAt(UUID receptionId);}
