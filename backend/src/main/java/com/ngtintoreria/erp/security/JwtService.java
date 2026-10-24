package com.ngtintoreria.erp.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes:480}") long expirationMinutes) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("ERP_JWT_SECRET debe tener al menos 32 caracteres");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String generate(UserDetails user, UUID jti, int securityVersion) {
        Instant now = Instant.now();
        List<String> authorities = user.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
        return Jwts.builder()
                .subject(user.getUsername())
                .id(jti.toString())
                .claim("authorities", authorities)
                .claim("sv", securityVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationMinutes * 60)))
                .signWith(key)
                .compact();
    }

    public String extractUsername(String token) { return claims(token).getSubject(); }
    public UUID extractJti(String token) { return UUID.fromString(claims(token).getId()); }
    public int extractSecurityVersion(String token) {
        Object value = claims(token).get("sv");
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }
    public OffsetDateTime extractExpiration(String token) {
        return OffsetDateTime.ofInstant(claims(token).getExpiration().toInstant(), ZoneOffset.UTC);
    }

    public boolean isValid(String token, UserDetails user, int expectedSecurityVersion) {
        try {
            Claims c = claims(token);
            Object sv = c.get("sv");
            int version = sv instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(sv));
            return user.getUsername().equalsIgnoreCase(c.getSubject())
                    && c.getExpiration().after(new Date())
                    && version == expectedSecurityVersion
                    && c.getId() != null;
        } catch (Exception ex) {
            return false;
        }
    }

    public long getExpirationMinutes() { return expirationMinutes; }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
