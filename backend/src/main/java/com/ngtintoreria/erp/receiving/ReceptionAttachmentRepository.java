package com.ngtintoreria.erp.receiving;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ReceptionAttachmentRepository extends JpaRepository<ReceptionAttachment,UUID>{
    List<ReceptionAttachment> findByReceptionIdOrderByCreatedAt(UUID receptionId);
    boolean existsByRollId(UUID rollId);
}
