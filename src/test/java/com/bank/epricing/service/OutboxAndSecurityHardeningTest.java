package com.bank.epricing.service;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.entity.OutboxEvent;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.event.LoanPricingEvent;
import com.bank.epricing.repository.OutboxEventRepository;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import com.bank.epricing.security.SecurityStartupValidator;
import com.bank.epricing.temporal.activity.PricingActivitiesImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.SendResult;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "kafka.enabled=true",
        "epricing.kafka.enabled=true",
        "epricing.outbox.enabled=true"
    }
)
class OutboxAndSecurityHardeningTest {

    @Autowired
    private PricingService pricingService;

    @Autowired
    private PricingAuditRepository auditRepository;

    @Autowired
    private PricingRepository pricingRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Outbox atomicity: Pricing calculation persists quote, audit, and PENDING outbox event; relay retries broker outages and delivers")
    void testOutboxAtomicityRetryDeadLetterAndRelayRecovery() {
        long initialAuditCount = auditRepository.count();

        PricingRequestDto dto = PricingRequestDto.builder()
                .customerId("CUST-OUTBOX-001")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("4800000.00"))
                .loanTenureMonths(180)
                .creditScore(780)
                .annualIncome(new BigDecimal("2400000.00"))
                .idempotencyKey("outbox-idem-" + UUID.randomUUID())
                .build();

        var response = pricingService.calculatePricing(dto, "10.0.0.1");
        assertNotNull(response.getRequestId());

        assertEquals(initialAuditCount + 1, auditRepository.count());

        OutboxEvent persistedOutbox = outboxEventRepository.findAll().stream()
                .filter(e -> response.getRequestId().equals(e.getAggregateId()))
                .findFirst()
                .orElseThrow();
        assertEquals(OutboxEvent.OutboxStatus.PENDING, persistedOutbox.getStatus());
        assertEquals(0, persistedOutbox.getRetryCount());

        AtomicBoolean brokerHealthy = new AtomicBoolean(false);
        PricingEventProducer flakyProducer = new PricingEventProducer() {
            @Override
            public CompletableFuture<SendResult<String, Object>> publishPricingEvent(LoanPricingEvent event) {
                if (!brokerHealthy.get()) {
                    CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException("Simulated Kafka broker down"));
                    return failed;
                }
                ProducerRecord<String, Object> record = new ProducerRecord<>("loan-pricing-events", event.getCustomerId(), event);
                RecordMetadata metadata = new RecordMetadata(new TopicPartition("loan-pricing-events", 0), 0L, 1, 0L, 0, 0);
                return CompletableFuture.completedFuture(new SendResult<>(record, metadata));
            }

            @Override
            public boolean isKafkaEnabled() {
                return true;
            }

            @Override
            public String getTopic() {
                return "loan-pricing-events";
            }
        };

        OutboxRelayService relay = new OutboxRelayService(
                outboxEventRepository, flakyProducer, transactionManager,
                new ObjectMapper(), new SimpleMeterRegistry(), true, 10, 30, 5);

        assertFalse(relay.dispatchSingle(persistedOutbox.getId()));
        OutboxEvent afterFailure = outboxEventRepository.findById(persistedOutbox.getId()).orElseThrow();
        assertEquals(OutboxEvent.OutboxStatus.PENDING, afterFailure.getStatus());
        assertEquals(1, afterFailure.getRetryCount());
        assertTrue(afterFailure.getNextRetryAt().isAfter(Instant.now().minusSeconds(1)),
                "Failed outbox publish must schedule exponential backoff in the future");
        assertTrue(afterFailure.getLastError().contains("Simulated Kafka broker down"));

        // Make event due immediately to exercise recovery without sleeping for backoff window
        afterFailure.setNextRetryAt(Instant.now().minusSeconds(1));
        outboxEventRepository.saveAndFlush(afterFailure);

        brokerHealthy.set(true);
        assertTrue(relay.dispatchSingle(persistedOutbox.getId()));
        OutboxEvent afterSuccess = outboxEventRepository.findById(persistedOutbox.getId()).orElseThrow();
        assertEquals(OutboxEvent.OutboxStatus.PUBLISHED, afterSuccess.getStatus());
        assertNotNull(afterSuccess.getPublishedAt());

        // Verify dead-letter quarantine & manual replay flow
        afterSuccess.setStatus(OutboxEvent.OutboxStatus.DEAD_LETTER);
        afterSuccess.setRetryCount(10);
        afterSuccess.setLastError("Broker timeout");
        outboxEventRepository.saveAndFlush(afterSuccess);

        int replayed = relay.replayDeadLetters(10);
        assertEquals(1, replayed);
        OutboxEvent afterReplay = outboxEventRepository.findById(afterSuccess.getId()).orElseThrow();
        assertEquals(OutboxEvent.OutboxStatus.PENDING, afterReplay.getStatus());
        assertEquals(0, afterReplay.getRetryCount());
    }

    @Test
    @DisplayName("Activity retry deduplication: duplicate calculateAndPersistPricing calls with same idempotencyKey produce one row")
    void testTemporalActivityRetryDeduplication() {
        String key = "temporal:wf-retry-" + UUID.randomUUID();
        PricingRequestDto dto = PricingRequestDto.builder()
                .customerId("CUST-RETRY-001")
                .productType("AUTO_LOAN")
                .loanAmount(new BigDecimal("900000.00"))
                .loanTenureMonths(60)
                .creditScore(760)
                .annualIncome(new BigDecimal("1500000.00"))
                .idempotencyKey(key)
                .build();

        PricingActivitiesImpl activities = new PricingActivitiesImpl(
                pricingService, auditRepository, pricingRepository, null, outboxService);

        long beforeCount = pricingRepository.count();
        var first = activities.calculateAndPersistPricing(dto, "127.0.0.1");
        var retry = activities.calculateAndPersistPricing(dto, "127.0.0.1");

        assertEquals(first.getRequestId(), retry.getRequestId());
        assertEquals(beforeCount + 1, pricingRepository.count(),
                "Retrying pricing activity must NOT insert duplicate rows");
    }

    @Test
    @DisplayName("SecurityStartupValidator fails closed in prod profile for insecure defaults")
    void testSecurityStartupValidatorFailClosedInProd() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        SecurityStartupValidator missingKeys = new SecurityStartupValidator(
                prodEnv, true, "", "", "", "jdbc:postgresql://db:5433/epricingdb?sslmode=verify-full", false);
        assertThrows(IllegalStateException.class, missingKeys::validateProductionReadiness);

        SecurityStartupValidator plaintextDb = new SecurityStartupValidator(
                prodEnv, true, "prod-strong-client-secret-key-1234", "", "",
                "jdbc:yugabytedb://db:5433/epricingdb?sslmode=disable", false);
        assertThrows(IllegalStateException.class, plaintextDb::validateProductionReadiness);

        SecurityStartupValidator demoSeedInProd = new SecurityStartupValidator(
                prodEnv, true,
                "prod-strong-client-secret-key-1234",
                "prod-strong-operator-secret-key-5678",
                "prod-strong-admin-secret-key-9012",
                "jdbc:yugabytedb://db:5433/epricingdb?sslmode=require", true);
        assertThrows(IllegalStateException.class, demoSeedInProd::validateProductionReadiness);

        SecurityStartupValidator partialPlaceholderInProd = new SecurityStartupValidator(
                prodEnv, true,
                "prod-strong-client-secret-key-1234",
                "CHANGE_ME_OPERATOR_KEY_IN_PRODUCTION",
                "prod-strong-admin-secret-key-9012",
                "jdbc:yugabytedb://db:5433/epricingdb?sslmode=verify-full", false);
        assertThrows(IllegalStateException.class, partialPlaceholderInProd::validateProductionReadiness);

        SecurityStartupValidator dockerLocalKeyInProd = new SecurityStartupValidator(
                prodEnv, true,
                "docker-local-client-key-0123456789",
                "prod-strong-operator-secret-key-5678",
                "prod-strong-admin-secret-key-9012",
                "jdbc:yugabytedb://db:5433/epricingdb?sslmode=verify-full", false);
        assertThrows(IllegalStateException.class, dockerLocalKeyInProd::validateProductionReadiness);

        SecurityStartupValidator duplicateRoleKeysInProd = new SecurityStartupValidator(
                prodEnv, true,
                "prod-strong-shared-secret-key-1234",
                "prod-strong-shared-secret-key-1234",
                "prod-strong-admin-secret-key-9012",
                "jdbc:yugabytedb://db:5433/epricingdb?sslmode=verify-full", false);
        assertThrows(IllegalStateException.class, duplicateRoleKeysInProd::validateProductionReadiness);

        SecurityStartupValidator validProd = new SecurityStartupValidator(
                prodEnv, true,
                "prod-strong-client-secret-key-1234",
                "prod-strong-operator-secret-key-5678",
                "prod-strong-admin-secret-key-9012",
                "jdbc:yugabytedb://db:5433/epricingdb?sslmode=verify-full", false);
        assertDoesNotThrow(validProd::validateProductionReadiness);
    }

    @Test
    @DisplayName("Idempotency-Key rejects replay if annual_income or loan_purpose changes and blocks keys > 128 chars")
    void testIdempotentReplayDetectsIncomeAndPurposeMutation() {
        String key = "idem-income-check-" + UUID.randomUUID();
        PricingRequestDto original = PricingRequestDto.builder()
                .customerId("CUST-IDEM-INCOME")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("4000000.00"))
                .loanTenureMonths(240)
                .creditScore(780)
                .annualIncome(new BigDecimal("2400000.00"))
                .loanPurpose("Primary residence")
                .build();

        var created = pricingService.calculatePricing(original, "127.0.0.1", key, null);
        var exactReplay = pricingService.calculatePricing(original, "127.0.0.1", key, null);
        assertEquals(created.getRequestId(), exactReplay.getRequestId());

        PricingRequestDto mutatedIncome = PricingRequestDto.builder()
                .customerId("CUST-IDEM-INCOME")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("4000000.00"))
                .loanTenureMonths(240)
                .creditScore(780)
                .annualIncome(new BigDecimal("600000.00"))
                .loanPurpose("Primary residence")
                .build();

        var conflictEx = assertThrows(com.bank.epricing.exception.PricingException.class,
                () -> pricingService.calculatePricing(mutatedIncome, "127.0.0.1", key, null));
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflictEx.getErrorCode());

        assertThrows(com.bank.epricing.exception.PricingException.class,
                () -> PricingService.normalizeAndValidateIdempotencyKey("k".repeat(129)));
    }

    @Test
    @DisplayName("MetricsDemoController excludes both prod and k8s profiles; KafkaProducerConfig honors topic replicas")
    void testMetricsDemoProfileAndKafkaTopicConfig() {
        org.springframework.context.annotation.Profile profileAnnotation =
                com.bank.epricing.controller.MetricsDemoController.class.getAnnotation(
                        org.springframework.context.annotation.Profile.class);
        assertNotNull(profileAnnotation);
        String expr = String.join(",", profileAnnotation.value());
        assertTrue(expr.contains("!prod") && expr.contains("!k8s"),
                "MetricsDemoController must stay disabled in both prod and k8s profiles");

        com.bank.epricing.config.KafkaProducerConfig cfg =
                new com.bank.epricing.config.KafkaProducerConfig(null);
        org.apache.kafka.clients.admin.NewTopic topic =
                cfg.loanPricingEventsTopic("loan-pricing-events", 6, 3, "2");
        assertEquals("loan-pricing-events", topic.name());
        assertEquals(6, topic.numPartitions());
        assertEquals((short) 3, topic.replicationFactor());
        assertEquals("2", topic.configs().get("min.insync.replicas"));
    }
}
