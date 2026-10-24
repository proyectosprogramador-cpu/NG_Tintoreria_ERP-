package com.ngtintoreria.erp.identity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, unique = true, length = 160)
    private String email;
    @Column(nullable = false, length = 160)
    private String displayName;
    @Column(nullable = false, length = 255)
    private String passwordHash;
    @Column(nullable = false)
    private boolean active = true;
    @Column(nullable = false)
    private boolean mustChangePassword = true;
    @Column(nullable = false)
    private int failedLoginAttempts = 0;
    private OffsetDateTime lockedUntil;
    private OffsetDateTime lastLoginAt;
    private OffsetDateTime passwordChangedAt;
    @Column(nullable = false)
    private int securityVersion = 1;
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected AppUser() {}
    public AppUser(String email, String displayName, String passwordHash) {
        this.email = email.toLowerCase(); this.displayName = displayName; this.passwordHash = passwordHash;
    }
    @PrePersist void prePersist() { createdAt = updatedAt = OffsetDateTime.now(); }
    @PreUpdate void preUpdate() { updatedAt = OffsetDateTime.now(); }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email.toLowerCase(); }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public void setFailedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }
    public OffsetDateTime getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(OffsetDateTime lockedUntil) { this.lockedUntil = lockedUntil; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(OffsetDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public OffsetDateTime getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(OffsetDateTime passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }
    public int getSecurityVersion() { return securityVersion; }
    public void setSecurityVersion(int securityVersion) { this.securityVersion = securityVersion; }
    public void incrementSecurityVersion() { this.securityVersion++; }
    public Set<Role> getRoles() { return roles; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
