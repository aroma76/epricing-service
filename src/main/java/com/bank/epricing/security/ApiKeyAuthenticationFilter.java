package com.bank.epricing.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Constant-time API key / Bearer token authentication filter assigning least-privilege roles:
 * ADMIN > OPERATOR > CLIENT.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final String clientApiKey;
    private final String operatorApiKey;
    private final String adminApiKey;

    public ApiKeyAuthenticationFilter(String clientApiKey, String operatorApiKey, String adminApiKey) {
        this.clientApiKey = normalize(clientApiKey);
        this.operatorApiKey = normalize(operatorApiKey);
        this.adminApiKey = normalize(adminApiKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String presented = extractToken(request);
        if (presented != null && !presented.isBlank()) {
            List<SimpleGrantedAuthority> authorities = resolveAuthorities(presented);
            if (!authorities.isEmpty()) {
                String principal = authorities.stream()
                        .map(SimpleGrantedAuthority::getAuthority)
                        .findFirst()
                        .orElse("ROLE_CLIENT");
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }

    private List<SimpleGrantedAuthority> resolveAuthorities(String presented) {
        if (constantTimeEquals(adminApiKey, presented)) {
            return List.of(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_OPERATOR"),
                    new SimpleGrantedAuthority("ROLE_CLIENT")
            );
        }
        if (constantTimeEquals(operatorApiKey, presented)) {
            return List.of(
                    new SimpleGrantedAuthority("ROLE_OPERATOR"),
                    new SimpleGrantedAuthority("ROLE_CLIENT")
            );
        }
        if (constantTimeEquals(clientApiKey, presented)) {
            return List.of(new SimpleGrantedAuthority("ROLE_CLIENT"));
        }
        return List.of();
    }

    private static String extractToken(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authHeader.substring(7).trim();
        }
        return null;
    }

    private static boolean constantTimeEquals(String configured, String presented) {
        if (configured == null || configured.isBlank() || presented == null || presented.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalize(String value) {
        return value != null ? value.trim() : "";
    }
}
