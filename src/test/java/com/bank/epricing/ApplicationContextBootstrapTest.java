package com.bank.epricing;

import com.bank.epricing.config.DatabaseMigrationInitializer;
import com.bank.epricing.controller.HealthController;
import com.bank.epricing.controller.PricingController;
import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.entity.PricingAuditLog;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.event.LoanPricingEvent;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import com.bank.epricing.service.PricingEventProducer;
import com.bank.epricing.service.PricingService;
import com.bank.epricing.temporal.activity.PricingActivities;
import com.bank.epricing.temporal.activity.PricingActivitiesImpl;
import com.bank.epricing.util.PricingCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.support.SendResult;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enterprise Application Context Bootstrap Test.
 *
 * Verifies:
 * 1. Full Spring Boot 3.3.4 context bootstrap without wiring or property resolution failures.
 * 2. Automatic migration execution via DatabaseMigrationInitializer.
 * 3. HikariCP DataSource initialization and active database connectivity.
 * 4. JPA entity mapping validation and repository wiring.
 * 5. Web MVC dispatcher, context path, and health endpoint response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationContextBootstrapTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PricingController pricingController;

    @Autowired
    private HealthController healthController;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private PricingRepository pricingRepository;

    @Autowired
    private PricingAuditRepository pricingAuditRepository;

    @Autowired
    private PricingActivitiesImpl pricingActivities;

    @Autowired
    private PricingCalculator pricingCalculator;

    @Autowired
    private DatabaseMigrationInitializer migrationInitializer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Application context boots successfully and all core banking beans are wired")
    void testContextLoadsAndBeansWired() {
        assertNotNull(applicationContext, "ApplicationContext must not be null");
        assertNotNull(pricingController, "PricingController must be wired");
        assertNotNull(healthController, "HealthController must be wired");
        assertNotNull(pricingService, "PricingService must be wired");
        assertNotNull(pricingRepository, "PricingRepository must be wired");
        assertNotNull(pricingCalculator, "PricingCalculator must be wired");
        assertNotNull(migrationInitializer, "DatabaseMigrationInitializer must be wired");
    }

    @Test
    @DisplayName("HikariCP DataSource establishes a valid database connection")
    void testDataSourceConnection() throws SQLException {
        assertNotNull(dataSource, "DataSource bean must be configured");
        try (Connection connection = dataSource.getConnection()) {
            assertNotNull(connection, "Active DB connection must be established");
            assertTrue(connection.isValid(5), "Connection must be valid");
        }
    }

    @Test
    @DisplayName("Health endpoint returns HTTP 200 with UP status via live HTTP container port")
    void testHealthEndpointLiveCall() {
        // TestRestTemplate automatically resolves relative paths against context-path (/api/v1)
        ResponseEntity<Map> response = restTemplate.getForEntity("/health", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    @DisplayName("ISSUE-09: Live container test of decoupled /health/liveness and /health/readiness")
    void testLiveHealthLivenessAndReadinessEndpoints() {
        ResponseEntity<Map> liveness = restTemplate.getForEntity("/health/liveness", Map.class);
        assertEquals(HttpStatus.OK, liveness.getStatusCode());
        assertNotNull(liveness.getBody());
        assertEquals("UP", liveness.getBody().get("status"));
        assertNotNull(liveness.getBody().get("jvm"));
        assertNull(liveness.getBody().get("database"));

        ResponseEntity<Map> readiness = restTemplate.getForEntity("/health/readiness", Map.class);
        assertEquals(HttpStatus.OK, readiness.getStatusCode());
        assertNotNull(readiness.getBody());
        assertEquals("UP", readiness.getBody().get("status"));
        assertNotNull(readiness.getBody().get("database"));
    }

    @Test
    @DisplayName("ISSUE-07: Real JPA persistence validates @Version column mapping, initial version 0, and version increment")
    void testRealJpaPersistenceAndOptimisticLocking() {
        com.bank.epricing.entity.PricingRequest entity = com.bank.epricing.entity.PricingRequest.builder()
                .customerId("CUST-LIVE-BOOT-01")
                .productType("HOME_LOAN")
                .loanAmount(new java.math.BigDecimal("5000000.00"))
                .loanTenureMonths(240)
                .creditScore(780)
                .annualIncome(new java.math.BigDecimal("2400000.00"))
                .status(com.bank.epricing.entity.PricingRequest.PricingStatus.CALCULATED)
                .build();

        com.bank.epricing.entity.PricingRequest saved = pricingRepository.save(entity);
        assertNotNull(saved.getId(), "Entity ID must be generated");
        assertNotNull(saved.getVersion(), "JPA @Version must be populated upon persist");
        assertEquals(0L, saved.getVersion(), "Initial optimistic version must be 0");

        saved.setStatus(com.bank.epricing.entity.PricingRequest.PricingStatus.APPROVED);
        com.bank.epricing.entity.PricingRequest updated = pricingRepository.save(saved);
        assertEquals(1L, updated.getVersion(), "JPA @Version must increment to 1 on update");
    }

    @Test
    @DisplayName("ISSUE-07: Real JPA entity concurrent collision throws OptimisticLockingFailureException")
    void testRealJpaOptimisticLockingCollision() {
        com.bank.epricing.entity.PricingRequest entity = com.bank.epricing.entity.PricingRequest.builder()
                .customerId("CUST-LIVE-COLLIDE-01")
                .productType("AUTO_LOAN")
                .loanAmount(new java.math.BigDecimal("800000.00"))
                .loanTenureMonths(60)
                .creditScore(720)
                .status(com.bank.epricing.entity.PricingRequest.PricingStatus.CALCULATED)
                .build();

        com.bank.epricing.entity.PricingRequest saved = pricingRepository.save(entity);
        Long id = saved.getId();
        Long initialVersion = saved.getVersion();

        // Simulate Thread A: loads and updates to APPROVED
        saved.setStatus(com.bank.epricing.entity.PricingRequest.PricingStatus.APPROVED);
        pricingRepository.save(saved);

        // Simulate Thread B: has stale reference with original version=0, attempts to update to EXPIRED
        com.bank.epricing.entity.PricingRequest staleCopy = com.bank.epricing.entity.PricingRequest.builder()
                .id(id)
                .version(initialVersion) // stale version 0
                .customerId("CUST-LIVE-COLLIDE-01")
                .productType("AUTO_LOAN")
                .loanAmount(new java.math.BigDecimal("800000.00"))
                .loanTenureMonths(60)
                .creditScore(720)
                .status(com.bank.epricing.entity.PricingRequest.PricingStatus.EXPIRED)
                .build();

        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () -> {
            pricingRepository.save(staleCopy);
        }, "Saving stale entity version must throw OptimisticLockingFailureException");
    }

    @Test
    @DisplayName("R1: Spring @Transactional PricingActivitiesImpl preserves terminal states and persists SKIPPED_TERMINAL_STATE audit logs in JPA")
    void testRealJpaTerminalStateGuardsInPricingActivities() {
        PricingRequest expiredQuote = pricingRepository.save(PricingRequest.builder()
                .customerId("CUST-R1-EXP-01")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("3000000.00"))
                .loanTenureMonths(180)
                .creditScore(760)
                .annualIncome(new BigDecimal("2000000.00"))
                .status(PricingRequest.PricingStatus.EXPIRED)
                .traceId("trace-r1-expired")
                .build());

        PricingRequest rejectedQuote = pricingRepository.save(PricingRequest.builder()
                .customerId("CUST-R1-REJ-01")
                .productType("AUTO_LOAN")
                .loanAmount(new BigDecimal("900000.00"))
                .loanTenureMonths(60)
                .creditScore(740)
                .annualIncome(new BigDecimal("1500000.00"))
                .status(PricingRequest.PricingStatus.REJECTED)
                .errorMessage("Customer declined")
                .traceId("trace-r1-rejected")
                .build());

        PricingRequest calculatedQuote = pricingRepository.save(PricingRequest.builder()
                .customerId("CUST-R1-CALC-01")
                .productType("PERSONAL_LOAN")
                .loanAmount(new BigDecimal("400000.00"))
                .loanTenureMonths(36)
                .creditScore(790)
                .annualIncome(new BigDecimal("1800000.00"))
                .status(PricingRequest.PricingStatus.CALCULATED)
                .traceId("trace-r1-calc")
                .build());

        // Late rejection against EXPIRED must not mutate entity
        pricingActivities.recordQuoteRejection(String.valueOf(expiredQuote.getId()), "CUST-R1-EXP-01", "Late rejection");
        PricingRequest reloadedExpired = pricingRepository.findById(expiredQuote.getId()).orElseThrow();
        assertEquals(PricingRequest.PricingStatus.EXPIRED, reloadedExpired.getStatus());
        assertEquals(0L, reloadedExpired.getVersion());
        assertNull(reloadedExpired.getErrorMessage());

        // Late expiration against REJECTED must not mutate entity
        pricingActivities.recordQuoteExpiration(String.valueOf(rejectedQuote.getId()), "CUST-R1-REJ-01");
        PricingRequest reloadedRejected = pricingRepository.findById(rejectedQuote.getId()).orElseThrow();
        assertEquals(PricingRequest.PricingStatus.REJECTED, reloadedRejected.getStatus());
        assertEquals(0L, reloadedRejected.getVersion());

        // Valid approval followed by late expiration and rejection must keep APPROVED at version 1
        pricingActivities.recordQuoteApproval(String.valueOf(calculatedQuote.getId()), "CUST-R1-CALC-01");
        pricingActivities.recordQuoteExpiration(String.valueOf(calculatedQuote.getId()), "CUST-R1-CALC-01");
        pricingActivities.recordQuoteRejection(String.valueOf(calculatedQuote.getId()), "CUST-R1-CALC-01", "Too late");

        PricingRequest reloadedApproved = pricingRepository.findById(calculatedQuote.getId()).orElseThrow();
        assertEquals(PricingRequest.PricingStatus.APPROVED, reloadedApproved.getStatus());
        assertEquals(1L, reloadedApproved.getVersion());

        List<PricingAuditLog> expiredAudits = pricingAuditRepository.findByPricingRequestIdOrderByCreatedAtAsc(expiredQuote.getId());
        assertTrue(expiredAudits.stream().anyMatch(a ->
                "RATE_REJECTED".equals(a.getAction()) && "SKIPPED_TERMINAL_STATE".equals(a.getOutcome())));

        List<PricingAuditLog> rejectedAudits = pricingAuditRepository.findByPricingRequestIdOrderByCreatedAtAsc(rejectedQuote.getId());
        assertTrue(rejectedAudits.stream().anyMatch(a ->
                "RATE_EXPIRED".equals(a.getAction()) && "SKIPPED_TERMINAL_STATE".equals(a.getOutcome())));

        List<PricingAuditLog> calcAudits = pricingAuditRepository.findByPricingRequestIdOrderByCreatedAtAsc(calculatedQuote.getId());
        assertEquals(1, calcAudits.stream().filter(a -> "SUCCESS".equals(a.getOutcome())).count());
        assertEquals(2, calcAudits.stream().filter(a -> "SKIPPED_TERMINAL_STATE".equals(a.getOutcome())).count());
    }

    @Test
    @DisplayName("R1: Concurrent stale-version modification throws ObjectOptimisticLockingFailureException inside PricingActivitiesImpl saveAndFlush with zero phantom audit/Kafka side effects")
    void testRealJpaConcurrentOptimisticLockingInPricingActivities() throws Exception {
        PricingRequest seeded = pricingRepository.saveAndFlush(PricingRequest.builder()
                .customerId("CUST-R1-RACE-01")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("3500000.00"))
                .loanTenureMonths(180)
                .creditScore(780)
                .annualIncome(new BigDecimal("2400000.00"))
                .status(PricingRequest.PricingStatus.CALCULATED)
                .traceId("trace-r1-race")
                .build());
        Long quoteId = seeded.getId();

        CopyOnWriteArrayList<LoanPricingEvent> publishedEvents = new CopyOnWriteArrayList<>();
        PricingEventProducer recordingProducer = new PricingEventProducer() {
            @Override
            public CompletableFuture<SendResult<String, Object>> publishPricingEvent(LoanPricingEvent event) {
                publishedEvents.add(event);
                return CompletableFuture.completedFuture(null);
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

        CountDownLatch threadBLoaded = new CountDownLatch(1);
        CountDownLatch threadACommitted = new CountDownLatch(1);
        AtomicBoolean saveAndFlushThrewOptimisticLock = new AtomicBoolean(false);

        PricingRepository interceptingRepo = (PricingRepository) Proxy.newProxyInstance(
                PricingRepository.class.getClassLoader(),
                new Class<?>[]{PricingRepository.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(pricingRepository, args);
                        if ("findById".equals(method.getName())
                                && "r1-optimistic-race-thread-b".equals(Thread.currentThread().getName())) {
                            threadBLoaded.countDown();
                            assertTrue(threadACommitted.await(5, TimeUnit.SECONDS),
                                    "Thread A must commit before Thread B flushes");
                        }
                        return result;
                    } catch (InvocationTargetException ex) {
                        Throwable target = ex.getTargetException();
                        if ("saveAndFlush".equals(method.getName())
                                && target instanceof ObjectOptimisticLockingFailureException) {
                            saveAndFlushThrewOptimisticLock.set(true);
                        }
                        throw target;
                    }
                }
        );

        TransactionInterceptor txInterceptor = new TransactionInterceptor(
                transactionManager,
                new AnnotationTransactionAttributeSource()
        );

        ProxyFactory factoryA = new ProxyFactory(new PricingActivitiesImpl(
                pricingService, pricingAuditRepository, pricingRepository, recordingProducer));
        factoryA.addInterface(PricingActivities.class);
        factoryA.addAdvice(txInterceptor);
        PricingActivities activitiesA = (PricingActivities) factoryA.getProxy();

        ProxyFactory factoryB = new ProxyFactory(new PricingActivitiesImpl(
                pricingService, pricingAuditRepository, interceptingRepo, recordingProducer));
        factoryB.addInterface(PricingActivities.class);
        factoryB.addAdvice(txInterceptor);
        PricingActivities activitiesB = (PricingActivities) factoryB.getProxy();

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "r1-optimistic-race-thread-b");
            t.setDaemon(true);
            return t;
        });

        try {
            Future<?> futureB = executor.submit(() ->
                    activitiesB.recordQuoteRejection(String.valueOf(quoteId), "CUST-R1-RACE-01", "Stale race rejection"));

            assertTrue(threadBLoaded.await(5, TimeUnit.SECONDS),
                    "Thread B must load entity at version 0 before Thread A commits");

            activitiesA.recordQuoteApproval(String.valueOf(quoteId), "CUST-R1-RACE-01");
            threadACommitted.countDown();

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> futureB.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ObjectOptimisticLockingFailureException.class, thrown.getCause());
            assertTrue(saveAndFlushThrewOptimisticLock.get(),
                    "ObjectOptimisticLockingFailureException must fire inside saveAndFlush within PricingActivitiesImpl");

            PricingRequest persisted = pricingRepository.findById(quoteId).orElseThrow();
            assertEquals(PricingRequest.PricingStatus.APPROVED, persisted.getStatus());
            assertEquals(1L, persisted.getVersion());
            assertNull(persisted.getErrorMessage());

            List<PricingAuditLog> audits = pricingAuditRepository.findByPricingRequestIdOrderByCreatedAtAsc(quoteId);
            assertEquals(1, audits.size(), "Rolled-back stale update must leave zero phantom audit rows");
            assertEquals("RATE_APPROVED", audits.get(0).getAction());
            assertEquals("SUCCESS", audits.get(0).getOutcome());

            assertEquals(1, publishedEvents.size(), "Rolled-back stale update must emit zero phantom Kafka events");
            assertEquals("APPROVED", publishedEvents.get(0).getStatus());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("R3: Live Spring web container validates enterprise hyphenated customer IDs and rejects invalid formats")
    void testLiveCustomerIdValidationThroughRestEndpoint() {
        PricingRequestDto validDto = PricingRequestDto.builder()
                .customerId("CUST-LIVE-001")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("4500000.00"))
                .loanTenureMonths(180)
                .creditScore(770)
                .annualIncome(new BigDecimal("2200000.00"))
                .build();

        ResponseEntity<Map> created = restTemplate.postForEntity("/pricing", validDto, Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertNotNull(created.getBody());
        assertEquals("CUST-LIVE-001", created.getBody().get("customer_id"));

        for (String invalidId : List.of("INVALID_ID", "CUST------", "cust000001")) {
            PricingRequestDto invalidDto = PricingRequestDto.builder()
                    .customerId(invalidId)
                    .productType("HOME_LOAN")
                    .loanAmount(new BigDecimal("4500000.00"))
                    .loanTenureMonths(180)
                    .creditScore(770)
                    .annualIncome(new BigDecimal("2200000.00"))
                    .build();

            ResponseEntity<Map> badRequest = restTemplate.postForEntity("/pricing", invalidDto, Map.class);
            assertEquals(HttpStatus.BAD_REQUEST, badRequest.getStatusCode());
            assertNotNull(badRequest.getBody());
            assertEquals("PRICING_VALIDATION_FAILED", badRequest.getBody().get("error_code"));
        }
    }
}
