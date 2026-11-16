package com.bank.epricing.temporal;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.temporal.activity.PricingActivities;
import com.bank.epricing.temporal.dto.PricingWorkflowResult;
import com.bank.epricing.temporal.workflow.LoanPricingWorkflow;
import com.bank.epricing.temporal.workflow.LoanPricingWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TemporalWorkflowLiveIntegrationTest {

    private WorkflowServiceStubs serviceStubs;
    private WorkflowClient client;
    private WorkerFactory workerFactory;
    private String taskQueue;

    static class StubPricingActivities implements PricingActivities {
        boolean approvalRecorded = false;

        @Override
        public PricingResponseDto calculateAndPersistPricing(PricingRequestDto requestDto, String requestIp) {
            PricingResponseDto quote = new PricingResponseDto();
            quote.setRequestId(999L);
            quote.setCustomerId(requestDto.getCustomerId());
            quote.setProductType(requestDto.getProductType());
            quote.setInterestRatePA(new BigDecimal("7.75"));
            quote.setCalculatedAt(java.time.LocalDateTime.now());
            return quote;
        }

        @Override
        public void recordQuoteApproval(String quoteId, String customerId) {
            this.approvalRecorded = true;
        }

        @Override
        public void recordQuoteRejection(String quoteId, String customerId, String reason) {}

        @Override
        public void recordQuoteExpiration(String quoteId, String customerId) {}
    }

    @BeforeEach
    void setUp() {
        taskQueue = "live-pricing-queue-" + UUID.randomUUID().toString().substring(0, 8);
        serviceStubs = WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget("localhost:7233")
                .build()
        );
        client = WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace("default")
                .build()
        );

        workerFactory = WorkerFactory.newInstance(client);
        Worker worker = workerFactory.newWorker(taskQueue);
        worker.registerWorkflowImplementationTypes(LoanPricingWorkflowImpl.class);
        worker.registerActivitiesImplementations(new StubPricingActivities());

        workerFactory.start();
    }

    @AfterEach
    void tearDown() {
        if (workerFactory != null) {
            workerFactory.shutdown();
        }
        if (serviceStubs != null) {
            serviceStubs.shutdown();
        }
    }

    @Test
    @DisplayName("Executes LoanPricingWorkflow on live Temporal Server: calculation, query, signal approval")
    void testLiveWorkflowExecution() {
        String workflowId = "test-live-" + UUID.randomUUID();
        LoanPricingWorkflow workflow = client.newWorkflowStub(
            LoanPricingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .build()
        );

        PricingRequestDto request = new PricingRequestDto();
        request.setCustomerId("CUST009999");
        request.setProductType("HOME_LOAN");
        request.setLoanAmount(new BigDecimal("6000000.00"));
        request.setLoanTenureMonths(240);
        request.setCreditScore(800);
        request.setAnnualIncome(new BigDecimal("2500000.00"));

        // Asynchronously start the workflow
        WorkflowClient.start(workflow::processLoanPricing, request, "127.0.0.1", 7);

        // Allow activity to process and state to reach QUOTED
        String status = "";
        for (int i = 0; i < 30; i++) {
            try {
                status = workflow.getStatus();
                if ("QUOTED".equals(status)) {
                    break;
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        assertEquals("QUOTED", status, "Workflow should reach QUOTED status");

        // Send approval signal
        workflow.approveQuote();

        // Wait for workflow completion and verify status
        for (int i = 0; i < 30; i++) {
            try {
                status = workflow.getStatus();
                if ("APPROVED".equals(status)) {
                    break;
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        assertEquals("APPROVED", status, "Workflow should transition to APPROVED after signal");

        PricingWorkflowResult result = workflow.getWorkflowResult();
        assertNotNull(result);
        assertEquals("APPROVED", result.getStatus());
        assertEquals("CUST009999", result.getCustomerId());
        assertNotNull(result.getQuote());
        assertEquals(new BigDecimal("7.75"), result.getQuote().getInterestRatePA());
    }
}
