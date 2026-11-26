# Comprehensive Test Strategy & Verification Architecture

**Frameworks:** JUnit 5 (Jupiter) + Mockito + Spring Boot Test (`@SpringBootTest`, `MockMvc`) + Temporal Test Environment (`TestWorkflowEnvironment`)  
**Test Root:** `src/test/java/com/bank/epricing/`  
**Execution Command (PowerShell):** `mvn clean test`

---

## 1. Testing Philosophy & Verification Integrity

The `epricing-service` verification architecture rejects the fragile practice of relying solely on isolated Mockito unit tests. While unit tests provide rapid feedback for arithmetic calculations, they cannot verify:
- Spring application context initialization and bean wiring.
- Database entity mapping annotations (`@Entity`, `@Table`, column types).
- Flyway/H2 schema migrations and SQL syntax validity.
- Kafka producer auto-configuration, serializers, and KRaft connectivity.
- Temporal workflow determinism and activity replay semantics.

Therefore, the test suite implements a **multi-tiered testing pyramid** consisting of **19 test classes** spanning **7 functional categories**, executing both lightweight unit tests and full `@SpringBootTest` context bootstrap validations.

```
                         ▲
                        / \
                       /   \   Category 1: Context Bootstrap (@SpringBootTest)
                      /     \  ApplicationContextBootstrapTest, KafkaContextBootstrapTest
                     /───────\
                    /         \  Category 4 & 5: Temporal & Kafka Integration
                   /           \ LoanPricingWorkflowTest, KafkaLiveBrokerIntegrationTest
                  /─────────────\
                 /               \  Category 3 & 6: Controller & Persistence Slices
                /                 \ PricingControllerTest, DatabaseMigrationInitializerTest
               /───────────────────\
              /                     \  Category 2 & 7: Pure Logic & Security Units
             /                       \ PricingCalculatorTest, StructuredLoggerTest
            └─────────────────────────┘
```

---

## 2. Master Test Inventory: 19 Classes Across 7 Functional Categories

| # | Functional Category | Test Class | Test Type / Annotations | Scope & Architectural Coverage |
|---|---------------------|------------|-------------------------|--------------------------------|
| 1 | **Context Bootstrap** | `ApplicationContextBootstrapTest` | `@SpringBootTest`, `@ActiveProfiles("h2")` | Validates Spring context loading, HikariCP pool creation, JPA entity validation, Actuator beans, and environment property resolution. |
| 2 | **Context Bootstrap** | `KafkaContextBootstrapTest` | `@SpringBootTest`, `@ActiveProfiles("h2")` | Verifies `KafkaProducerConfig`, `DefaultKafkaProducerFactory`, `KafkaTemplate`, and Kafka property bindings. |
| 3 | **Business Logic** | `PricingCalculatorTest` | Pure Unit Test (No mocks) | Validates reducing balance EMI formulas, risk tier adjustments, credit score thresholds, and boundary conditions. |
| 4 | **Business Logic** | `PricingServiceTest` | Unit Test (Mockito) | Verifies orchestration of eligibility, calculation, audit persistence, and asynchronous Kafka domain event dispatch. |
| 5 | **REST Controllers** | `PricingControllerTest` | `MockMvc` (Standalone) | Tests synchronous `/api/v1/pricing` contract, HTTP status codes (201, 400, 404, 422), and validation error responses. |
| 6 | **REST Controllers** | `HealthControllerTest` | `MockMvc` (Standalone) | Tests `/api/v1/health` custom liveness and readiness probe responses. |
| 7 | **REST Controllers** | `TemporalPricingControllerTest` | `MockMvc` (Standalone) | Verifies durable workflow REST API (`/api/v1/workflow/pricing/*`), including start, approve, reject, and status queries. |
| 8 | **Temporal Workflows** | `LoanPricingWorkflowTest` | In-Memory Temporal Test | Executes `LoanPricingWorkflowImpl` inside Temporal's `TestWorkflowEnvironment`, testing timers, approval signals, and expirations. |
| 9 | **Temporal Workflows** | `PricingActivitiesImplTest` | Unit Test (Mockito) | Validates activity methods, database updates for quote states, and `publishStatusEvent` calls. |
| 10 | **Temporal Workflows** | `TemporalPricingServiceTest` | Unit Test (Mockito) | Tests Temporal workflow stub instantiation, signal dispatching, and workflow client management. |
| 11 | **Temporal Workflows** | `TemporalWorkflowLiveIntegrationTest` | Integration Test | Verifies end-to-end workflow execution against a live Temporal server instance. |
| 12 | **Messaging & Kafka** | `LoanPricingEventTest` | Jackson JSON Test | Tests bidirectional serialization/deserialization, snake_case/camelCase aliases, and ISO-8601 millisecond timestamps. |
| 13 | **Messaging & Kafka** | `PricingEventProducerTest` | Unit Test (Mockito) | Verifies `KafkaPricingEventProducer`, 3000ms fail-fast exception handling, disabled flag handling, and traceparent injection. |
| 14 | **Messaging & Kafka** | `KafkaLiveBrokerIntegrationTest` | Integration Test | Publishes domain events to a real or embedded Kafka broker and verifies topic partition assignment and metadata. |
| 15 | **Database & Migrations**| `DatabaseMigrationInitializerTest` | H2 Integration Test | Executes custom migration runner (V1 through V8 scripts) against PostgreSQL-compatible H2 mode to verify DDL integrity. |
| 16 | **Database & Persistence**| `PricingPersistenceAtomicityTest` | Persistence Test | Verifies that `pricing_requests` and `pricing_audit_logs` are committed atomically, preventing orphaned rows. |
| 17 | **Security & Logging** | `StructuredLoggerTest` | Unit Test | Tests PII masking (`maskCustomerId()`), coarse amount bracketing (`amountBracket()`), and Logback JSON structured formatting. |
| 18 | **DTO Contracts & Paging**| `PagedResponseTest` | Unit Test | Validates pagination metadata, page indexing, and immutable wrapper contracts for `PagedResponse`. |
| 19 | **DTO Validation** | `PricingRequestDtoValidationTest` | Parameterized Bean Validation | Validates 19 boundary and adversarial customer ID, loan amount, tenure, and product type Jakarta validation rules. |

---

## 3. Deep-Dive: Core Test Suites

### 3.1 Context Bootstrap Tests (`ApplicationContextBootstrapTest` & `KafkaContextBootstrapTest`)
These tests bootstrap the full Spring Boot application context (`@SpringBootTest`) using the `h2` profile:

```java
@SpringBootTest
@ActiveProfiles("h2")
public class ApplicationContextBootstrapTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private PricingRepository pricingRepository;

    @Test
    @DisplayName("Application context must bootstrap successfully with all required beans")
    void contextLoads() {
        assertNotNull(applicationContext, "ApplicationContext should not be null");
        assertNotNull(pricingService, "PricingService bean must be wired");
        assertNotNull(pricingRepository, "PricingRepository bean must be wired");
    }
}
```
**Why this matters**: As noted in enterprise engineering guardrails, Mockito unit tests that mock repositories cannot catch broken JPA entity mappings, missing application properties, or circular dependency deadlocks. Full bootstrap tests catch these defects before code leaves CI.

### 3.2 In-Memory Temporal Workflow Tests (`LoanPricingWorkflowTest`)
Utilizes Temporal's official `TestWorkflowEnvironment` to simulate durable time and signals without running a local Temporal daemon:

```java
@Test
void testQuoteApprovalSignalWithinValidityWindow() {
    testEnv.registerWorkflowImplementationTypes(LoanPricingWorkflowImpl.class);
    worker.registerActivitiesImplementations(mockActivities);
    testEnv.start();

    LoanPricingWorkflow workflow = client.newWorkflowStub(LoanPricingWorkflow.class, ...);
    WorkflowExecution execution = WorkflowClient.start(workflow::processLoanPricing, requestDto, "127.0.0.1", 7);

    // Fast-forward simulated durable time by 2 days
    testEnv.sleep(Duration.ofDays(2));

    // Signal approval
    workflow.approveQuote();

    PricingWorkflowResult result = workflow.getWorkflowResult();
    assertEquals("APPROVED", result.getStatus());
    verify(mockActivities).recordQuoteApproval(anyString(), anyString());
}
```

### 3.3 Kafka Event Streaming Tests (`PricingEventProducerTest` & `LoanPricingEventTest`)
Verifies that `LoanPricingEvent` JSON serialization adheres to cross-platform contracts and tests the 3000ms fail-fast timeout:

```java
@Test
@DisplayName("Producer should record exception on active span and complete exceptionally when Kafka times out")
void testProducerFailFastTimeout() {
    when(kafkaOperations.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Kafka broker timeout after 3000ms")));

    CompletableFuture<SendResult<String, Object>> future = producer.publishPricingEvent(testEvent);

    assertTrue(future.isCompletedExceptionally());
    verify(span).setStatus(eq(StatusCode.ERROR), contains("Kafka broker timeout"));
}
```

---

## 4. Test Execution & CI Commands

All commands adhere to **Windows PowerShell** syntax invariants (using `;` instead of `&&`):

```powershell
# Run the complete test suite (all 19 test classes)
mvn clean test

# Run only Context Bootstrap Tests
mvn test -Dtest=ApplicationContextBootstrapTest,KafkaContextBootstrapTest

# Run only Temporal Workflow Tests
mvn test -Dtest=LoanPricingWorkflowTest,PricingActivitiesImplTest,TemporalPricingControllerTest

# Run only Kafka Messaging Tests
mvn test -Dtest=LoanPricingEventTest,PricingEventProducerTest,KafkaContextBootstrapTest

# Run with full Surefire failure details
mvn test -Dsurefire.failIfNoSpecifiedTests=false
```

---

## 5. Verification Gate & Quality Checklist

Before committing changes or cutting releases:
1. **Context Loading**: Both `@SpringBootTest` bootstrap tests pass with zero context startup errors.
2. **Deterministic Replay**: In-memory Temporal workflow tests pass with zero non-deterministic method calls.
3. **Fail-Fast Producer**: Kafka producer tests pass under simulated broker failure without throwing unhandled runtime exceptions.
4. **PII Sanitization**: All log output in `StructuredLoggerTest` contains masked identifiers (`CUST****1234`).
5. **Zero Test Regressions**: All 19 test classes complete with 0 failures and 0 errors.
