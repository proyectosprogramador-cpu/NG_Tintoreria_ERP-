package com.ngtintoreria.erp.security;

import com.ngtintoreria.erp.identity.AppUser;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name="password_reset_tokens")
public class PasswordResetToken {
    @Id
    private UUID id;

    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    @JoinColumn(name="user_id", nullable=false)
    private AppUser user;

    @Column(name="token_hash", nullable=false, unique=true, length=64)
    private String tokenHash;

    @Column(name="created_at", nullable=false)
    private OffsetDateTime createdAt;

    @Column(name="expires_at", nullable=false)
    private OffsetDateTime expiresAt;

    @Column(name="used_at")
    private OffsetDateTime usedAt;

    @Column(name="requested_ip", length=80)
    private String requestedIp;

    protected PasswordResetToken() {}

    public PasswordResetToken(AppUser user,String tokenHash,OffsetDateTime createdAt,OffsetDateTime expiresAt,String requestedIp){
        this.id=UUID.randomUUID();
        this.user=user;
        this.tokenHash=tokenHash;
        this.createdAt=createdAt;
        this.expiresAt=expiresAt;
        this.requestedIp=requestedIp;
    }

    public UUID getId(){return id;}
    public AppUser getUser(){return user;}
    public String getTokenHash(){return tokenHash;}
    public OffsetDateTime getCreatedAt(){return createdAt;}
    public OffsetDateTime getExpiresAt(){return expiresAt;}
    public OffsetDateTime getUsedAt(){return usedAt;}
    public void setUsedAt(OffsetDateTime usedAt){this.usedAt=usedAt;}
    public String getRequestedIp(){return requestedIp;}
}
