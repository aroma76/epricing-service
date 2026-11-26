package com.bank.epricing.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Safely resolves client IP addresses without blindly trusting spoofable proxy headers,
 * and sanitizes untrusted header values against CRLF/control-character log injection.
 */
@Component
public class ClientIpResolver {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\r\\n\\t\\p{Cntrl}]");

    private final boolean trustForwardedHeaders;

    public ClientIpResolver(@Value("${epricing.security.trust-forwarded-headers:false}") boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        if (trustForwardedHeaders) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
                return sanitize(xForwardedFor.split(",")[0].trim(), 50);
            }
            String xRealIp = request.getHeader("X-Real-IP");
            if (xRealIp != null && !xRealIp.isBlank()) {
                return sanitize(xRealIp.trim(), 50);
            }
        }
        return sanitize(request.getRemoteAddr(), 50);
    }

    public static String sanitize(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        String cleaned = CONTROL_CHARS.matcher(input).replaceAll("_").trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
