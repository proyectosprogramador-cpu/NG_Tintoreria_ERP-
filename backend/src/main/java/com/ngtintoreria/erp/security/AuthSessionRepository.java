package com.ngtintoreria.erp.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    Optional<AuthSession> findByJti(UUID jti);

    @Query("select s from AuthSession s join fetch s.user u where u.id=:userId and s.revokedAt is null and s.expiresAt>:now order by s.issuedAt desc")
    List<AuthSession> findActiveByUser(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    @Query("select s from AuthSession s join fetch s.user u where s.revokedAt is null and s.expiresAt>:now order by s.lastSeenAt desc")
    List<AuthSession> findAllActive(@Param("now") OffsetDateTime now);

    @Query("select count(s) from AuthSession s where s.revokedAt is null and s.expiresAt>:now")
    long countActive(@Param("now") OffsetDateTime now);

    @Transactional
    @Modifying
    @Query("update AuthSession s set s.revokedAt=:now where s.user.id=:userId and s.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);
}
