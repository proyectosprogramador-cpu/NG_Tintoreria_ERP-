package com.ngtintoreria.erp.security;

import com.ngtintoreria.erp.identity.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final AppUserRepository users;
    private final AuthSessionRepository sessions;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService,
                                   AppUserRepository users, AuthSessionRepository sessions) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.users = users;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7);
            try {
                String username = jwtService.extractUsername(token);
                var appUser = users.findByEmailIgnoreCase(username).orElse(null);
                if (appUser != null && appUser.isActive() && (appUser.getLockedUntil() == null || !appUser.getLockedUntil().isAfter(OffsetDateTime.now()))) {
                    UserDetails details = userDetailsService.loadUserByUsername(username);
                    var jti = jwtService.extractJti(token);
                    var session = sessions.findByJti(jti).orElse(null);
                    boolean activeSession = session != null
                            && session.getRevokedAt() == null
                            && session.getExpiresAt().isAfter(OffsetDateTime.now());
                    if (activeSession && jwtService.isValid(token, details, appUser.getSecurityVersion())) {
                        var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        if (session.getLastSeenAt().isBefore(OffsetDateTime.now().minusMinutes(5))) {
                            session.setLastSeenAt(OffsetDateTime.now());
                            sessions.save(session);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Token o sesión inválida: la cadena continúa y SecurityConfig responderá 401 si la ruta lo exige.
            }
        }
        filterChain.doFilter(request, response);
    }
}
