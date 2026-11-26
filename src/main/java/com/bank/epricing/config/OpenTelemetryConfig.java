package com.bank.epricing.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  OpenTelemetryConfig.java — OTel SDK Initialization                     ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHY THIS CLASS EXISTS:                                                  ║
 * ║  The OpenTelemetry SDK needs to be initialized ONCE at startup with:   ║
 * ║    - Where to send traces (OTLP endpoint)                              ║
 * ║    - Service name (appears in Grafana Tempo)                           ║
 * ║    - Sampling rate (what % of requests to trace)                       ║
 * ║                                                                          ║
 * ║  This @Configuration class registers Spring Beans for:                 ║
 * ║    1. OpenTelemetry — the main SDK object                             ║
 * ║    2. Tracer — used in PricingService to create custom spans          ║
 * ║                                                                          ║
 * ║  WHY NOT JUST USE AUTO-CONFIGURATION:                                   ║
 * ║  Spring Boot + Micrometer Tracing provides some auto-config for OTel.  ║
 * ║  But we need a Tracer bean to inject into PricingService for creating  ║
 * ║  CUSTOM child spans. This class bridges OTel SDK and Spring's DI.      ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Configuration
public class OpenTelemetryConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    @Value("${spring.application.name}")
    private String serviceName;

    @Value("${otel.exporter.otlp.endpoint:http://localhost:4318}")
    private String otlpEndpoint;

    @Value("${spring.profiles.active:local}")
    private String activeProfile;

    @Value("${otel.traces.sampler:parentbased_always_on}")
    private String tracesSampler;

    @Value("${otel.traces.sampler.arg:}")
    private String tracesSamplerArg;

    @Bean
    public OpenTelemetry openTelemetry() {
        log.info("Initializing OpenTelemetry SDK | serviceName={} | endpoint={}",
            serviceName, otlpEndpoint);

        try {
            return AutoConfiguredOpenTelemetrySdk.builder()
                .setResultAsGlobal()
                .addPropertiesSupplier(this::buildOtelProperties)
                .build()
                .getOpenTelemetrySdk();
        } catch (Exception e) {
            log.warn("GlobalOpenTelemetry already registered in JVM or failed to register as global ({}); reusing existing global or creating standalone SDK", e.getMessage());
            try {
                return io.opentelemetry.api.GlobalOpenTelemetry.get();
            } catch (Exception ex) {
                return AutoConfiguredOpenTelemetrySdk.builder()
                    .addPropertiesSupplier(this::buildOtelProperties)
                    .build()
                    .getOpenTelemetrySdk();
            }
        }
    }

    private Map<String, String> buildOtelProperties() {
        Map<String, String> props = new HashMap<>();
        props.put("otel.service.name", serviceName);
        props.put("otel.exporter.otlp.endpoint", otlpEndpoint);
        props.put("otel.exporter.otlp.protocol", "http/protobuf");
        props.put("otel.traces.sampler", tracesSampler);
        if (tracesSamplerArg != null && !tracesSamplerArg.isBlank()) {
            props.put("otel.traces.sampler.arg", tracesSamplerArg);
        }
        props.put("otel.resource.attributes",
            "service.namespace=Bank,team.name=epricing,deployment.environment=" + activeProfile);
        return props;
    }

    /**
     * Creates a Tracer bean for the epricing service.
     *
     * The Tracer is the OTel component responsible for creating spans.
     * We inject this into PricingService to create custom child spans.
     *
     * "com.bank.epricing" → instrumentation library name
     *   This appears in the trace as the "scope" or "instrumentation library"
     *   Helps distinguish spans created by your code vs library auto-instrumentation.
     *
     * "1.0.0" → version of your instrumentation code
     */
    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("com.bank.epricing", "1.0.0");
    }
}
