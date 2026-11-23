package com.bank.epricing.service;

import com.bank.epricing.event.LoanPricingEvent;
import com.bank.epricing.logging.StructuredLogger;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Apache Kafka implementation of PricingEventProducer for publishing loan pricing domain events.
 * Incorporates OpenTelemetry distributed tracing and structured logging.
 */
@Service
public class KafkaPricingEventProducer implements PricingEventProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaPricingEventProducer.class);

    private final KafkaOperations<String, Object> kafkaOperations;
    private final Tracer tracer;
    private final MeterRegistry meterRegistry;
    private final boolean kafkaEnabled;
    private final String topic;

    @Autowired
    public KafkaPricingEventProducer(
            @Autowired(required = false) KafkaOperations<String, Object> kafkaOperations,
            @Autowired(required = false) Tracer tracer,
            @Autowired(required = false) MeterRegistry meterRegistry,
            @Value("${kafka.enabled:${epricing.kafka.enabled:true}}") boolean kafkaEnabled,
            @Value("${kafka.topic:${spring.kafka.template.default-topic:${epricing.kafka.topic:loan-pricing-events}}}") String topic) {
        this.kafkaOperations = kafkaOperations;
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
        this.kafkaEnabled = kafkaEnabled;
        this.topic = topic;
    }

    public KafkaPricingEventProducer(
            KafkaOperations<String, Object> kafkaOperations,
            Tracer tracer,
            boolean kafkaEnabled,
            String topic) {
        this(kafkaOperations, tracer, null, kafkaEnabled, topic);
    }

    @Override
    public CompletableFuture<SendResult<String, Object>> publishPricingEvent(LoanPricingEvent event) {
        if (event == null) {
            log.warn("Null loan pricing event received; skipping publish");
            return CompletableFuture.completedFuture(null);
        }

        if (!kafkaEnabled || kafkaOperations == null) {
            log.info("Kafka publishing is disabled (kafka.enabled={}). Skipped event for requestId={}",
                    kafkaEnabled, event.getRequestId());
            return CompletableFuture.completedFuture(null);
        }

        String customerIdMasked = StructuredLogger.maskCustomerId(event.getCustomerId());
        log.info("Publishing loan pricing event to Kafka | topic={} | requestId={} | customerId={} | traceId={}",
                topic, event.getRequestId(), customerIdMasked, event.getTraceId());

        Span span = null;
        if (tracer != null) {
            // ISSUE-05 FIX: Use masked customer ID and omit cleartext PII from span attributes
            String maskedId = (event.getCustomerId() != null && !event.getCustomerId().isBlank())
                    ? StructuredLogger.maskCustomerId(event.getCustomerId())
                    : "";
            span = tracer.spanBuilder("kafka.publish." + topic)
                    .setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("messaging.system", "kafka")
                    .setAttribute("messaging.destination.name", topic)
                    .setAttribute("messaging.operation", "publish")
                    .setAttribute("messaging.kafka.message.key", maskedId)
                    .setAttribute("pricing.request.id", event.getRequestId() != null ? event.getRequestId().toString() : "")
                    .setAttribute("customer.id.masked", maskedId)
                    .startSpan();
        }

        ProducerRecord<String, Object> record = new ProducerRecord<>(
                topic,
                event.getCustomerId(),
                event
        );

        if (span != null && span.getSpanContext().isValid()) {
            String traceFlags = (span.getSpanContext().getTraceFlags() != null)
                    ? span.getSpanContext().getTraceFlags().asHex()
                    : (span.getSpanContext().isSampled() ? "01" : "00");
            record.headers().add("traceparent",
                    ("00-" + span.getSpanContext().getTraceId() + "-" + span.getSpanContext().getSpanId() + "-" + traceFlags)
                            .getBytes(StandardCharsets.UTF_8));
        }

        final Span activeSpan = span;

        try {
            CompletableFuture<SendResult<String, Object>> future = kafkaOperations.send(record);

            future.whenComplete((result, ex) -> {
                try {
                    if (ex != null) {
                        if (activeSpan != null) {
                            activeSpan.setStatus(StatusCode.ERROR, ex.getMessage());
                            activeSpan.recordException(ex);
                        }
                        if (meterRegistry != null) {
                            meterRegistry.counter("epricing.kafka.publish.failed", "topic", topic).increment();
                        }
                        log.error("Failed to publish loan pricing event to Kafka | topic={} | requestId={} | error={}",
                                topic, event.getRequestId(), ex.getMessage(), ex);
                    } else {
                        if (activeSpan != null) {
                            activeSpan.setStatus(StatusCode.OK);
                        }
                        if (meterRegistry != null) {
                            meterRegistry.counter("epricing.kafka.publish.success", "topic", topic).increment();
                        }
                        RecordMetadata rm = result != null ? result.getRecordMetadata() : null;
                        int partition = rm != null ? rm.partition() : -1;
                        long offset = rm != null ? rm.offset() : -1;
                        log.info("Successfully published loan pricing event to Kafka | topic={} | partition={} | offset={} | requestId={} | traceId={}",
                                topic, partition, offset, event.getRequestId(), event.getTraceId());
                    }
                } finally {
                    if (activeSpan != null) {
                        activeSpan.end();
                    }
                }
            });

            return future;
        } catch (Exception e) {
            if (activeSpan != null) {
                activeSpan.setStatus(StatusCode.ERROR, e.getMessage());
                activeSpan.recordException(e);
                activeSpan.end();
            }
            if (meterRegistry != null) {
                meterRegistry.counter("epricing.kafka.publish.failed", "topic", topic).increment();
            }
            log.error("Error submitting loan pricing event to Kafka | topic={} | requestId={} | error={}",
                    topic, event.getRequestId(), e.getMessage(), e);
            CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
        }
    }

    @Override
    public boolean isKafkaEnabled() {
        return kafkaEnabled;
    }

    @Override
    public String getTopic() {
        return topic;
    }
}
