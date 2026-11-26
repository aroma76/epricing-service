package com.bank.epricing.service;

import com.bank.epricing.entity.OutboxEvent;
import com.bank.epricing.event.LoanPricingEvent;
import com.bank.epricing.logging.StructuredLogger;
import com.bank.epricing.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Transaction-participating service for writing domain events to the outbox_events table.
 * Enforces atomic DB + Kafka event persistence within the caller's active database transaction.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final boolean outboxEnabled;
    private final boolean kafkaEnabled;
    private final String defaultTopic;
    private final int maxRetries;

    @Autowired
    public OutboxService(
            OutboxEventRepository outboxEventRepository,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Value("${epricing.outbox.enabled:true}") boolean outboxEnabled,
            @Value("${kafka.enabled:true}") boolean kafkaEnabled,
            @Value("${epricing.kafka.enabled:true}") boolean epricingKafkaEnabled,
            @Value("${spring.kafka.template.default-topic:${kafka.topic:loan-pricing-events}}") String defaultTopic,
            @Value("${epricing.outbox.max-retries:10}") int maxRetries) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = configuredMapper(objectMapper);
        this.outboxEnabled = outboxEnabled;
        this.kafkaEnabled = kafkaEnabled && epricingKafkaEnabled;
        this.defaultTopic = defaultTopic;
        this.maxRetries = maxRetries;
    }

    /** Shared ISO-8601 JavaTime ObjectMapper factory for outbox serialization & relay deserialization. */
    public static ObjectMapper configuredMapper(ObjectMapper base) {
        ObjectMapper mapper = base != null ? base.copy() : new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    public boolean isEnabled() {
        return outboxEnabled && kafkaEnabled;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public OutboxEvent enqueue(String aggregateType, Long aggregateId, LoanPricingEvent event) {
        try {
            OutboxEvent outbox = new OutboxEvent();
            outbox.setAggregateType(aggregateType);
            outbox.setAggregateId(aggregateId != null ? aggregateId : 0L);
            outbox.setEventType("LOAN_PRICING_" + (event.getStatus() != null ? event.getStatus() : "EVENT"));
            outbox.setTopic(defaultTopic);
            outbox.setMessageKey(event.getCustomerId() != null
                    ? event.getCustomerId()
                    : String.valueOf(aggregateId));
            outbox.setPayload(objectMapper.writeValueAsString(event));
            outbox.setTraceId(event.getTraceId());
            outbox.setStatus(OutboxEvent.OutboxStatus.PENDING);
            outbox.setRetryCount(0);
            outbox.setMaxRetries(maxRetries);
            outbox.setNextRetryAt(Instant.now());
            return outboxEventRepository.save(outbox);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to serialize outbox event for aggregate=" + aggregateType
                            + " id=" + aggregateId
                            + " customer=" + StructuredLogger.maskCustomerId(event.getCustomerId()),
                    ex);
        }
    }
}
