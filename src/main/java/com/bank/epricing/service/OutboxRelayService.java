package com.bank.epricing.service;

import com.bank.epricing.entity.OutboxEvent;
import com.bank.epricing.event.LoanPricingEvent;
import com.bank.epricing.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls PENDING outbox events, claims leases atomically without holding DB locks across Kafka I/O,
 * retries with bounded exponential backoff, and quarantines unrecoverable events in DEAD_LETTER.
 */
@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final PricingEventProducer pricingEventProducer;
    private final TransactionTemplate requiresNewTx;
    private final ObjectMapper objectMapper;
    private final boolean outboxEnabled;
    private final int batchSize;
    private final long leaseSeconds;
    private final long sendTimeoutSeconds;
    private final String workerId = "outbox-" + UUID.randomUUID().toString().substring(0, 8);

    private final AtomicLong pendingGaugeCount = new AtomicLong(0);
    private final AtomicLong deadLetterGaugeCount = new AtomicLong(0);
    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter deadLetterCounter;

    @Autowired
    public OutboxRelayService(
            OutboxEventRepository outboxEventRepository,
            @Autowired(required = false) PricingEventProducer pricingEventProducer,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) MeterRegistry meterRegistry,
            @Value("${epricing.outbox.enabled:true}") boolean outboxEnabled,
            @Value("${epricing.outbox.batch-size:25}") int batchSize,
            @Value("${epricing.outbox.lease-seconds:30}") long leaseSeconds,
            @Value("${epricing.outbox.send-timeout-seconds:5}") long sendTimeoutSeconds) {
        this.outboxEventRepository = outboxEventRepository;
        this.pricingEventProducer = pricingEventProducer;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.objectMapper = OutboxService.configuredMapper(objectMapper);
        this.outboxEnabled = outboxEnabled;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.sendTimeoutSeconds = sendTimeoutSeconds;

        if (meterRegistry != null) {
            Gauge.builder("epricing.outbox.pending", pendingGaugeCount, AtomicLong::get)
                    .description("Number of outbox events waiting to be published to Kafka")
                    .register(meterRegistry);
            Gauge.builder("epricing.outbox.dead_letter", deadLetterGaugeCount, AtomicLong::get)
                    .description("Number of outbox events quarantined in DEAD_LETTER state")
                    .register(meterRegistry);
            this.publishedCounter = Counter.builder("epricing.outbox.published")
                    .description("Total outbox events successfully delivered to Kafka")
                    .register(meterRegistry);
            this.failedCounter = Counter.builder("epricing.outbox.failed")
                    .description("Total outbox Kafka delivery failures")
                    .register(meterRegistry);
            this.deadLetterCounter = Counter.builder("epricing.outbox.dead_letter.total")
                    .description("Total outbox events moved to DEAD_LETTER after exhausting retries")
                    .register(meterRegistry);
        } else {
            this.publishedCounter = null;
            this.failedCounter = null;
            this.deadLetterCounter = null;
        }
    }

    @Scheduled(fixedDelayString = "${epricing.outbox.poll-interval-ms:1000}")
    public int relayPendingEvents() {
        if (!outboxEnabled || pricingEventProducer == null || !pricingEventProducer.isKafkaEnabled()) {
            return 0;
        }
        refreshGaugesSafe();

        Instant now = Instant.now();
        List<OutboxEvent> candidates = outboxEventRepository.findDispatchCandidates(now, PageRequest.of(0, batchSize));
        int publishedCount = 0;

        for (OutboxEvent candidate : candidates) {
            if (dispatchSingle(candidate.getId())) {
                publishedCount++;
            }
        }
        refreshGaugesSafe();
        return publishedCount;
    }

    public boolean dispatchSingle(Long eventId) {
        if (!outboxEnabled || pricingEventProducer == null || !pricingEventProducer.isKafkaEnabled()) {
            return false;
        }
        Instant now = Instant.now();
        Instant leaseUntil = now.plusSeconds(leaseSeconds);

        OutboxEvent claimed = requiresNewTx.execute(status -> {
            int updated = outboxEventRepository.claimEvent(eventId, workerId, now, leaseUntil);
            if (updated != 1) {
                return null;
            }
            return outboxEventRepository.findById(eventId).orElse(null);
        });

        if (claimed == null) {
            return false;
        }

        try {
            LoanPricingEvent domainEvent = objectMapper.readValue(claimed.getPayload(), LoanPricingEvent.class);
            pricingEventProducer.publishPricingEvent(domainEvent).get(sendTimeoutSeconds, TimeUnit.SECONDS);

            requiresNewTx.executeWithoutResult(status -> outboxEventRepository.findById(eventId).ifPresent(evt -> {
                evt.setStatus(OutboxEvent.OutboxStatus.PUBLISHED);
                evt.setPublishedAt(Instant.now());
                evt.setLockedBy(null);
                evt.setLockedUntil(null);
                evt.setLastError(null);
                outboxEventRepository.save(evt);
            }));
            if (publishedCounter != null) {
                publishedCounter.increment();
            }
            return true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            handleFailure(eventId, ex);
            return false;
        }
    }

    private void handleFailure(Long eventId, Exception ex) {
        if (failedCounter != null) {
            failedCounter.increment();
        }
        String rawMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        String safeError = rawMsg.length() > 1997 ? rawMsg.substring(0, 1997) + "..." : rawMsg;

        requiresNewTx.executeWithoutResult(status -> outboxEventRepository.findById(eventId).ifPresent(evt -> {
            int nextAttempt = (evt.getRetryCount() != null ? evt.getRetryCount() : 0) + 1;
            int limit = evt.getMaxRetries() != null ? evt.getMaxRetries() : 10;
            evt.setRetryCount(nextAttempt);
            evt.setLockedBy(null);
            evt.setLockedUntil(null);
            evt.setLastError(safeError);

            if (nextAttempt >= limit) {
                evt.setStatus(OutboxEvent.OutboxStatus.DEAD_LETTER);
                if (deadLetterCounter != null) {
                    deadLetterCounter.increment();
                }
                log.error("CRITICAL: Outbox event moved to DEAD_LETTER | outboxId={} | aggregateId={} | attempts={} | error={}",
                        eventId, evt.getAggregateId(), nextAttempt, safeError);
            } else {
                long backoffSeconds = Math.min(300L, (long) Math.pow(2, Math.min(nextAttempt, 8)));
                evt.setStatus(OutboxEvent.OutboxStatus.PENDING);
                evt.setNextRetryAt(Instant.now().plus(Duration.ofSeconds(backoffSeconds)));
                log.warn("Outbox Kafka publish failed, scheduled retry | outboxId={} | aggregateId={} | attempt={}/{} | nextRetrySec={} | error={}",
                        eventId, evt.getAggregateId(), nextAttempt, limit, backoffSeconds, safeError);
            }
            outboxEventRepository.save(evt);
        }));
    }

    public int replayDeadLetters(int limit) {
        List<OutboxEvent> deadLetters = outboxEventRepository.findByStatusOrderByCreatedAtDesc(
                OutboxEvent.OutboxStatus.DEAD_LETTER,
                PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
        Instant now = Instant.now();
        Integer requeued = requiresNewTx.execute(status -> {
            int count = 0;
            for (OutboxEvent evt : deadLetters) {
                evt.setStatus(OutboxEvent.OutboxStatus.PENDING);
                evt.setRetryCount(0);
                evt.setNextRetryAt(now);
                evt.setLockedBy(null);
                evt.setLockedUntil(null);
                evt.setLastError(null);
                outboxEventRepository.save(evt);
                count++;
            }
            return count;
        });
        refreshGaugesSafe();
        return requeued != null ? requeued : 0;
    }

    private void refreshGaugesSafe() {
        try {
            pendingGaugeCount.set(outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.PENDING));
            deadLetterGaugeCount.set(outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.DEAD_LETTER));
        } catch (Exception ignored) {
            // Avoid failing relay loop on metrics update
        }
    }
}
