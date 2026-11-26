package com.bank.epricing.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Fail-closed startup guard preventing production/Kubernetes deployments from starting
 * with security disabled, missing API keys, demo seed enabled, or plaintext DB transport.
 */
@Component
public class SecurityStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupValidator.class);

    private final Environment environment;
    private final boolean securityEnabled;
    private final String clientApiKey;
    private final String operatorApiKey;
    private final String adminApiKey;
    private final String datasourceUrl;
    private final boolean seedDemoData;

    public SecurityStartupValidator(
            Environment environment,
            @Value("${epricing.security.enabled:true}") boolean securityEnabled,
            @Value("${epricing.security.client-api-key:}") String clientApiKey,
            @Value("${epricing.security.operator-api-key:}") String operatorApiKey,
            @Value("${epricing.security.admin-api-key:}") String adminApiKey,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${epricing.migrations.seed-demo-data:false}") boolean seedDemoData) {
        this.environment = environment;
        this.securityEnabled = securityEnabled;
        this.clientApiKey = clientApiKey;
        this.operatorApiKey = operatorApiKey;
        this.adminApiKey = adminApiKey;
        this.datasourceUrl = datasourceUrl;
        this.seedDemoData = seedDemoData;
    }

    @PostConstruct
    public void validateProductionReadiness() {
        String[] activeProfiles = environment != null ? environment.getActiveProfiles() : new String[0];
        boolean isProdOrK8s = Arrays.stream(activeProfiles)
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "k8s".equalsIgnoreCase(p));

        if (!isProdOrK8s) {
            return;
        }

        if (!securityEnabled) {
            throw new IllegalStateException(
                    "SECURITY GUARDRAIL VIOLATION: epricing.security.enabled must be true in prod/k8s profiles");
        }
        if (isWeakOrMissing(clientApiKey) || isWeakOrMissing(operatorApiKey) || isWeakOrMissing(adminApiKey)) {
            throw new IllegalStateException(
                    "SECURITY GUARDRAIL VIOLATION: client, operator, and admin API keys must all be strong (>=16 chars, non-placeholder) in prod/k8s profiles");
        }
        if (clientApiKey.trim().equals(operatorApiKey.trim())
                || clientApiKey.trim().equals(adminApiKey.trim())
                || operatorApiKey.trim().equals(adminApiKey.trim())) {
            throw new IllegalStateException(
                    "SECURITY GUARDRAIL VIOLATION: client, operator, and admin API keys must be distinct to enforce least-privilege RBAC");
        }
        if (datasourceUrl != null && datasourceUrl.contains("sslmode=disable")) {
            throw new IllegalStateException(
                    "SECURITY GUARDRAIL VIOLATION: Plaintext JDBC sslmode=disable is forbidden in prod/k8s profiles");
        }
        if (seedDemoData) {
            throw new IllegalStateException(
                    "DATA INTEGRITY GUARDRAIL VIOLATION: epricing.migrations.seed-demo-data must be false in prod/k8s profiles");
        }
        log.info("Verified fail-closed production security and data integrity guardrails");
    }

    private static boolean isWeakOrMissing(String key) {
        if (key == null || key.isBlank() || key.trim().length() < 16) {
            return true;
        }
        String upper = key.trim().toUpperCase();
        return upper.contains("CHANGE_ME")
                || upper.contains("PLACEHOLDER")
                || upper.startsWith("DEV-")
                || upper.startsWith("DOCKER-LOCAL");
    }
}
