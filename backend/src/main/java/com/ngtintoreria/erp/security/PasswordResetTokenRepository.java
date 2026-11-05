package com.ngtintoreria.erp.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    Optional<PasswordResetToken> findByTokenHashAndUsedAtIsNull(String tokenHash);
    Optional<PasswordResetToken> findTopByUserIdOrderByCreatedAtDesc(UUID userId);
    long deleteByExpiresAtBefore(OffsetDateTime before);
}
