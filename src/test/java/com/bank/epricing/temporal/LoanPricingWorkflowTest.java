package com.bank.epricing.temporal;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.temporal.activity.PricingActivities;
import com.bank.epricing.temporal.dto.PricingWorkflowResult;
import com.bank.epricing.temporal.workflow.LoanPricingWorkflow;
import com.bank.epricing.temporal.workflow.LoanPricingWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LoanPricingWorkflowTest {

    private TestWorkflowEnvironment testEnv;
    private Worker worker;
    private WorkflowClient client;
    private TestPricingActivities activitiesStub;
    private static final String TASK_QUEUE = "test-pricing-queue";

    static class TestPricingActivities implements PricingActivities {
        PricingResponseDto quoteResponse;
        RuntimeException calculationException;
        CountDownLatch calcStartedLatch;
        CountDownLatch allowCalcCompleteLatch;
        int calculateCount = 0;
        int approvalCount = 0;
        int rejectionCount = 0;
        int expirationCount = 0;
        String lastRecordedReason;
        String lastQuoteId;
        String lastCustomerId;

        @Override
        public PricingResponseDto calculateAndPersistPricing(PricingRequestDto requestDto, String requestIp) {
            calculateCount++;
            if (calcStartedLatch != null) {
                calcStartedLatch.countDown();
            }
            if (allowCalcCompleteLatch != null) {
                try {
                    allowCalcCompleteLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (calculationException != null) {
                throw calculationException;
            }
            return quoteResponse;
        }

        @Override
        public void recordQuoteApproval(String quoteId, String customerId) {
            approvalCount++;
            this.lastQuoteId = quoteId;
            this.lastCustomerId = customerId;
        }

        @Override
        public void recordQuoteRejection(String quoteId, String customerId, String reason) {
            rejectionCount++;
            this.lastQuoteId = quoteId;
            this.lastCustomerId = customerId;
            this.lastRecordedReason = reason;
        }

        @Override
        public void recordQuoteExpiration(String quoteId, String customerId) {
            expirationCount++;
            this.lastQuoteId = quoteId;
            this.lastCustomerId = customerId;
        }
    }

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(LoanPricingWorkflowImpl.class);

        activitiesStub = new TestPricingActivities();
        worker.registerActivitiesImplementations(activitiesStub);

        client = testEnv.getWorkflowClient();
        testEnv.start();
    }

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    private PricingRequestDto createSampleRequest() {
        PricingRequestDto dto = new PricingRequestDto();
        dto.setCustomerId("CUST001234");
        dto.setProductType("HOME_LOAN");
        dto.setLoanAmount(new BigDecimal("5000000"));
        dto.setLoanTenureMonths(240);
        dto.setCreditScore(780);
        dto.setAnnualIncome(new BigDecimal("2400000"));
        return dto;
    }

    private PricingResponseDto createSampleQuote() {
        PricingResponseDto quote = new PricingResponseDto();
        quote.setRequestId(101L);
        quote.setCustomerId("CUST001234");
        quote.setProductType("HOME_LOAN");
        quote.setInterestRatePA(new BigDecimal("7.50"));
        quote.setEmiAmount(new BigDecimal("40280.00"));
        quote.setRiskCategory("LOW");
        quote.setCalculatedAt(java.time.LocalDateTime.now());
        return quote;
    }

    private void awaitQuotedStatus(LoanPricingWorkflow workflow) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!"QUOTED".equals(workflow.getStatus()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals("QUOTED", workflow.getStatus());
    }

    @Test
    @DisplayName("Workflow approves quote when customer signals approval before rate-lock expiry")
    void testWorkflowApprovalSignalBeforeExpiry() {
        PricingRequestDto request = createSampleRequest();
        activitiesStub.quoteResponse = createSampleQuote();

        LoanPricingWorkflow workflow = client.newWorkflowStub(
            LoanPricingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId("wf-test-approve")
                .build()
        );

        WorkflowClient.start(workflow::processLoanPricing, request, "127.0.0.1", 7);

        // Await transition to QUOTED state
        awaitQuotedStatus(workflow);

        // Signal customer approval
        workflow.approveQuote();

        // Get synchronous final result
        PricingWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(PricingWorkflowResult.class);

        assertNotNull(result);
        assertEquals("APPROVED", result.getStatus());
        assertEquals("CUST001234", result.getCustomerId());
        assertEquals(new BigDecimal("7.50"), result.getQuote().getInterestRatePA());

        assertEquals(1, activitiesStub.calculateCount);
        assertEquals(1, activitiesStub.approvalCount);
        assertEquals("101", activitiesStub.lastQuoteId);
        assertEquals("CUST001234", activitiesStub.lastCustomerId);
        assertEquals(0, activitiesStub.rejectionCount);
        assertEquals(0, activitiesStub.expirationCount);
    }

    @Test
    @DisplayName("Workflow rejects quote when customer signals rejection with reason")
    void testWorkflowRejectionSignalBeforeExpiry() {
        PricingRequestDto request = createSampleRequest();
        activitiesStub.quoteResponse = createSampleQuote();

        LoanPricingWorkflow workflow = client.newWorkflowStub(
            LoanPricingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId("wf-test-reject")
                .build()
        );

        WorkflowClient.start(workflow::processLoanPricing, request, "127.0.0.1", 7);

        awaitQuotedStatus(workflow);

        // Signal customer rejection
        workflow.rejectQuote("Customer chose competitor rate 7.25%");

        PricingWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(PricingWorkflowResult.class);

        assertNotNull(result);
        assertEquals("REJECTED", result.getStatus());
        assertEquals("Customer chose competitor rate 7.25%", result.getRejectionReason());

        assertEquals(1, activitiesStub.calculateCount);
        assertEquals(1, activitiesStub.rejectionCount);
        assertEquals("101", activitiesStub.lastQuoteId);
        assertEquals("CUST001234", activitiesStub.lastCustomerId);
        assertEquals("Customer chose competitor rate 7.25%", activitiesStub.lastRecordedReason);
        assertEquals(0, activitiesStub.approvalCount);
        assertEquals(0, activitiesStub.expirationCount);
    }

    @Test
    @DisplayName("Workflow automatically expires rate lock after 7 days virtual time without signals")
    void testWorkflowRateLockExpiryAfter7Days() {
        PricingRequestDto request = createSampleRequest();
        activitiesStub.quoteResponse = createSampleQuote();

        LoanPricingWorkflow workflow = client.newWorkflowStub(
            LoanPricingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId("wf-test-expiry")
                .build()
        );

        WorkflowClient.start(workflow::processLoanPricing, request, "127.0.0.1", 7);

        awaitQuotedStatus(workflow);

        // Fast-forward virtual time past 7-day rate-lock window
        testEnv.sleep(Duration.ofDays(7));

        PricingWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(PricingWorkflowResult.class);

        assertNotNull(result);
        assertEquals("EXPIRED", result.getStatus());
        assertNotNull(result.getUpdatedAt());

        assertEquals(1, activitiesStub.calculateCount);
        assertEquals(1, activitiesStub.expirationCount);
        assertEquals("101", activitiesStub.lastQuoteId);
        assertEquals("CUST001234", activitiesStub.lastCustomerId);
        assertEquals(0, activitiesStub.approvalCount);
        assertEquals(0, activitiesStub.rejectionCount);
    }

    @Test
    @DisplayName("Workflow terminates immediately with REJECTED status if calculation activity fails")
    void testWorkflowCalculationFailureImmediateRejection() {
        PricingRequestDto request = createSampleRequest();
        activitiesStub.calculationException = ApplicationFailure.newNonRetryableFailure(
            "Credit score 620 below minimum threshold of 650",
            "InsufficientCreditScoreException"
        );

        LoanPricingWorkflow workflow = client.newWorkflowStub(
            LoanPricingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId("wf-test-calc-fail")
                .build()
        );

        // Execute synchronously — fails immediately on activity step
        PricingWorkflowResult result = workflow.processLoanPricing(request, "127.0.0.1", 7);

        assertNotNull(result);
        assertEquals("REJECTED", result.getStatus());
        assertTrue(result.getRejectionReason().contains("Credit score 620"));

        assertEquals(1, activitiesStub.calculateCount);
        assertEquals(0, activitiesStub.approvalCount);
        assertEquals(0, activitiesStub.rejectionCount);
        assertEquals(0, activitiesStub.expirationCount);
    }

    @Test
    @DisplayName("Should replay pre-versioned Nov 19 early-signal history without NonDeterministicException")
    void testReplayNov19EarlySignalHistoryWithoutNonDeterminism() {
        assertDoesNotThrow(() ->
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                        "temporal/nov19-early-signal-history.json",
                        LoanPricingWorkflowImpl.class
                )
        );
    }

    @Test
    @DisplayName("New executions accept early approval signals received while calculation is running")
    void testEarlyApprovalSignalDuringCalculationSucceedsImmediately() throws Exception {
        PricingRequestDto request = createSampleRequest();
        activitiesStub.quoteResponse = createSampleQuote();
        activitiesStub.calcStartedLatch = new CountDownLatch(1);
        activitiesStub.allowCalcCompleteLatch = new CountDownLatch(1);

        LoanPricingWorkflow workflow = client.newWorkflowStub(
                LoanPricingWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId("wf-test-early-approve")
                        .build()
        );

        WorkflowClient.start(workflow::processLoanPricing, request, "127.0.0.1", 7);

        assertTrue(activitiesStub.calcStartedLatch.await(5, TimeUnit.SECONDS),
                "Calculation activity should have started");
        workflow.approveQuote();
        activitiesStub.allowCalcCompleteLatch.countDown();

        PricingWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(PricingWorkflowResult.class);
        assertNotNull(result);
        assertEquals("APPROVED", result.getStatus());
        assertEquals(1, activitiesStub.calculateCount);
        assertEquals(1, activitiesStub.approvalCount);
        assertEquals(0, activitiesStub.expirationCount);
    }
}
