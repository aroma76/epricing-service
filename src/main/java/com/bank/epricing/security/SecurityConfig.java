package com.bank.epricing.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

import java.time.Instant;
import java.util.Map;

/**
 * Configures stateless Spring Security with API-key/Bearer RBAC when epricing.security.enabled=true.
 * Public probes (/health/**, /actuator/health/**, /actuator/info, /actuator/prometheus) remain reachable;
 * pricing business & admin endpoints require authenticated roles.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${epricing.security.enabled:true}")
    private boolean securityEnabled;

    @Value("${epricing.security.client-api-key:}")
    private String clientApiKey;

    @Value("${epricing.security.operator-api-key:}")
    private String operatorApiKey;

    @Value("${epricing.security.admin-api-key:}")
    private String adminApiKey;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<HandlerMappingIntrospector> introspectorProvider) throws Exception {
        if (introspectorProvider.getIfAvailable() != null) {
            http.cors(Customizer.withDefaults());
        }
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!securityEnabled) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        ApiKeyAuthenticationFilter authFilter =
                new ApiKeyAuthenticationFilter(clientApiKey, operatorApiKey, adminApiKey);

        ObjectMapper mapper = new ObjectMapper();

        http
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) -> writeSecurityError(
                        res, mapper, HttpServletResponse.SC_UNAUTHORIZED,
                        "UNAUTHORIZED", "Missing or invalid API credentials", req.getRequestURI()))
                .accessDeniedHandler((req, res, accessEx) -> writeSecurityError(
                        res, mapper, HttpServletResponse.SC_FORBIDDEN,
                        "FORBIDDEN", "Insufficient privileges for requested pricing resource", req.getRequestURI()))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(
                    "/health/**",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/actuator/prometheus",
                    "/error"
                ).permitAll()
                .requestMatchers("/actuator/**", "/admin/**").hasAnyRole("OPERATOR", "ADMIN")
                .requestMatchers("/metrics-demo/**").hasAnyRole("OPERATOR", "ADMIN")
                .requestMatchers("/pricing/**", "/workflow/pricing/**").hasAnyRole("CLIENT", "OPERATOR", "ADMIN")
                .anyRequest().authenticated()
            );

        return http.build();
    }

    private static void writeSecurityError(HttpServletResponse response,
                                           ObjectMapper mapper,
                                           int status,
                                           String code,
                                           String message,
                                           String path) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), Map.of(
                "timestamp", Instant.now().toString(),
                "status", status,
                "error_code", code,
                "message", message,
                "path", path != null ? path : ""
        ));
    }
}
