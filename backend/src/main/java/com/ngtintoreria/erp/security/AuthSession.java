package com.ngtintoreria.erp.security;

import com.ngtintoreria.erp.identity.AppUser;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "auth_sessions")
public class AuthSession {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @Column(nullable = false, unique = true)
    private UUID jti;
    @Column(nullable = false)
    private OffsetDateTime issuedAt;
    @Column(nullable = false)
    private OffsetDateTime expiresAt;
    @Column(nullable = false)
    private OffsetDateTime lastSeenAt;
    private OffsetDateTime revokedAt;
    @Column(length = 80)
    private String ipAddress;
    @Column(length = 500)
    private String userAgent;

    protected AuthSession() {}

    public AuthSession(AppUser user, UUID jti, OffsetDateTime issuedAt, OffsetDateTime expiresAt, String ipAddress, String userAgent) {
        this.user = user;
        this.jti = jti;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.lastSeenAt = issuedAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public UUID getId() { return id; }
    public AppUser getUser() { return user; }
    public UUID getJti() { return jti; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
}
