package com.bank.epricing.temporal.activity;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.entity.PricingAuditLog;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.event.LoanPricingEvent;
import com.bank.epricing.metrics.PricingMetrics;
import com.bank.epricing.logging.StructuredLogger;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import com.bank.epricing.service.PricingAuditService;
import com.bank.epricing.service.PricingEventProducer;
import com.bank.epricing.service.PricingService;
import com.bank.epricing.util.PricingCalculator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PricingActivitiesImplTest {

    private PricingService pricingService;
    private PricingAuditRepository auditRepository;
    private PricingRepository pricingRepository;
    private PricingEventProducer pricingEventProducer;
    private PricingActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        pricingRepository = mock(PricingRepository.class);
        auditRepository = mock(PricingAuditRepository.class);
        pricingEventProducer = mock(PricingEventProducer.class);

        PricingCalculator pricingCalculator = new PricingCalculator();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PricingMetrics pricingMetrics = new PricingMetrics();
        pricingMetrics.bindTo(meterRegistry);

        PricingAuditService auditService = new PricingAuditService(auditRepository, pricingRepository);
        StructuredLogger structuredLogger = new StructuredLogger();

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        SpanBuilder spanBuilder = mock(SpanBuilder.class);
        SpanContext spanContext = mock(SpanContext.class);

        when(spanContext.getTraceId()).thenReturn("act-test-trace-id");
        when(span.getSpanContext()).thenReturn(spanContext);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);

        // Stub publishPricingEvent to return a completed future (avoids NPE in publishStatusEvent lambda)
        when(pricingEventProducer.publishPricingEvent(any())).thenReturn(CompletableFuture.completedFuture(null));

        pricingService = new PricingService(
                pricingRepository,
                pricingCalculator,
                pricingMetrics,
                auditService,
                structuredLogger,
                tracer,
                pricingEventProducer
        );

        activities = new PricingActivitiesImpl(
                pricingService,
                auditRepository,
                pricingRepository,
                pricingEventProducer
        );
    }

    @Test
    @DisplayName("Should delegate calculateAndPersistPricing to PricingService with fallback IP")
    void testCalculateAndPersistPricing() {
        PricingRequestDto dto = PricingRequestDto.builder()
                .customerId("CUST-ACT-001")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("1000000"))
                .loanTenureMonths(120)
                .creditScore(750)
                .annualIncome(new BigDecimal("1200000"))
                .build();

        PricingRequest savedEntity = PricingRequest.builder()
                .id(101L)
                .customerId("CUST-ACT-001")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("1000000"))
                .loanTenureMonths(120)
                .calculatedRate(new BigDecimal("8.50"))
                .emiAmount(new BigDecimal("12398.57"))
                .status(PricingRequest.PricingStatus.CALCULATED)
                .build();

        when(pricingRepository.save(any(PricingRequest.class))).thenReturn(savedEntity);

        PricingResponseDto actual = activities.calculateAndPersistPricing(dto, null);
        assertNotNull(actual);
        assertEquals(101L, actual.getRequestId());
        assertEquals("CUST-ACT-001", actual.getCustomerId());
        verify(pricingRepository, times(1)).save(any(PricingRequest.class));
    }

    @Test
    @DisplayName("Should record quote approval, update entity status, audit log, and publish Kafka event")
    void testRecordQuoteApproval_Success() {
        PricingRequest request = PricingRequest.builder()
                .id(201L)
                .customerId("CUST-ACT-002")
                .productType("AUTO_LOAN")
                .loanAmount(new BigDecimal("800000"))
                .calculatedRate(new BigDecimal("9.50"))
                .emiAmount(new BigDecimal("10500.00"))
                .loanTenureMonths(84)
                .status(PricingRequest.PricingStatus.CALCULATED)
                .traceId("trace-approval-test")
                .build();

        when(pricingRepository.findById(201L)).thenReturn(Optional.of(request));
        when(pricingRepository.saveAndFlush(any(PricingRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        activities.recordQuoteApproval("201", "CUST-ACT-002");

        assertEquals(PricingRequest.PricingStatus.APPROVED, request.getStatus());
        verify(pricingRepository, times(1)).saveAndFlush(request);

        ArgumentCaptor<PricingAuditLog> auditCaptor = ArgumentCaptor.forClass(PricingAuditLog.class);
        verify(auditRepository, times(1)).save(auditCaptor.capture());
        PricingAuditLog capturedAudit = auditCaptor.getValue();
        assertEquals(201L, capturedAudit.getPricingRequestId());
        assertEquals("RATE_APPROVED", capturedAudit.getAction());
        assertEquals("trace-approval-test", capturedAudit.getTraceId());

        ArgumentCaptor<LoanPricingEvent> eventCaptor = ArgumentCaptor.forClass(LoanPricingEvent.class);
        verify(pricingEventProducer, times(1)).publishPricingEvent(eventCaptor.capture());
        LoanPricingEvent capturedEvent = eventCaptor.getValue();
        assertEquals(201L, capturedEvent.getRequestId());
        assertEquals("APPROVED", capturedEvent.getStatus());
        assertEquals("trace-approval-test", capturedEvent.getTraceId());
    }

    @Test
    @DisplayName("Should record quote rejection with long reason truncated and publish REJECTED event")
    void testRecordQuoteRejection_LongReasonTruncated() {
        PricingRequest request = PricingRequest.builder()
                .id(301L)
                .customerId("CUST-ACT-003")
                .productType("PERSONAL_LOAN")
                .loanAmount(new BigDecimal("300000"))
                .status(PricingRequest.PricingStatus.CALCULATED)
                .traceId("trace-rejection-test")
                .build();

        when(pricingRepository.findById(301L)).thenReturn(Optional.of(request));
        when(pricingRepository.saveAndFlush(any(PricingRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String longReason = "R".repeat(600);
        activities.recordQuoteRejection("301", "CUST-ACT-003", longReason);

        assertEquals(PricingRequest.PricingStatus.REJECTED, request.getStatus());
        assertTrue(request.getErrorMessage().length() <= 500);
        assertTrue(request.getErrorMessage().endsWith("..."));

        verify(pricingRepository, times(1)).saveAndFlush(request);
        verify(auditRepository, times(1)).save(any(PricingAuditLog.class));

        ArgumentCaptor<LoanPricingEvent> eventCaptor = ArgumentCaptor.forClass(LoanPricingEvent.class);
        verify(pricingEventProducer, times(1)).publishPricingEvent(eventCaptor.capture());
        assertEquals("REJECTED", eventCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("Should record quote expiration, update status to EXPIRED, and publish EXPIRED event")
    void testRecordQuoteExpiration_Success() {
        PricingRequest request = PricingRequest.builder()
                .id(401L)
                .customerId("CUST-ACT-004")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("4000000"))
                .status(PricingRequest.PricingStatus.CALCULATED)
                .traceId("trace-expiration-test")
                .build();

        when(pricingRepository.findById(401L)).thenReturn(Optional.of(request));
        when(pricingRepository.saveAndFlush(any(PricingRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        activities.recordQuoteExpiration("401", "CUST-ACT-004");

        assertEquals(PricingRequest.PricingStatus.EXPIRED, request.getStatus());
        verify(pricingRepository, times(1)).saveAndFlush(request);
        verify(auditRepository, times(1)).save(any(PricingAuditLog.class));

        ArgumentCaptor<LoanPricingEvent> eventCaptor = ArgumentCaptor.forClass(LoanPricingEvent.class);
        verify(pricingEventProducer, times(1)).publishPricingEvent(eventCaptor.capture());
        assertEquals("EXPIRED", eventCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("Should handle unparseable quoteId and missing entity gracefully without crashing")
    void testHandleInvalidQuoteIdGracefully() {
        activities.recordQuoteApproval("not-a-number", "CUST-ACT-005");
        verify(pricingRepository, never()).findById(anyLong());
        verify(pricingEventProducer, never()).publishPricingEvent(any());

        activities.recordQuoteApproval(null, "CUST-ACT-005");
        verify(pricingRepository, never()).findById(anyLong());

        activities.recordQuoteApproval("   ", "CUST-ACT-005");
        verify(pricingRepository, never()).findById(anyLong());

        verify(auditRepository, times(3)).save(any(PricingAuditLog.class));
        verify(pricingEventProducer, never()).publishPricingEvent(any());
    }

    @Test
    @DisplayName("Should handle non-existent quote in repository gracefully without publishing Kafka event")
    void testRecordQuoteApproval_NotFound() {
        when(pricingRepository.findById(999L)).thenReturn(Optional.empty());

        activities.recordQuoteApproval("999", "CUST-ACT-999");

        verify(pricingRepository, never()).save(any());
        verify(pricingRepository, never()).saveAndFlush(any());
        verify(pricingEventProducer, never()).publishPricingEvent(any());

        ArgumentCaptor<PricingAuditLog> captor = ArgumentCaptor.forClass(PricingAuditLog.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("NOT_FOUND", captor.getValue().getOutcome());
    }

    @Test
    @DisplayName("Should handle 3-arg constructor when PricingEventProducer is omitted")
    void testThreeArgConstructor() {
        PricingActivitiesImpl fallbackActivities = new PricingActivitiesImpl(
                pricingService,
                auditRepository,
                pricingRepository
        );

        PricingRequest request = PricingRequest.builder()
                .id(501L)
                .customerId("CUST-ACT-006")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("2000000"))
                .status(PricingRequest.PricingStatus.CALCULATED)
                .build();

        when(pricingRepository.findById(501L)).thenReturn(Optional.of(request));
        when(pricingRepository.saveAndFlush(any(PricingRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Should complete without NullPointerException even though producer is null
        assertDoesNotThrow(() -> fallbackActivities.recordQuoteApproval("501", "CUST-ACT-006"));
        verify(auditRepository, times(1)).save(any(PricingAuditLog.class));
    }

    @Test
    @DisplayName("ISSUE-07: Should propagate OptimisticLockingFailureException when concurrent update collides")
    void testRecordQuoteApproval_OptimisticLockingFailure() {
        PricingRequest request = PricingRequest.builder()
                .id(601L)
                .customerId("CUST-ACT-007")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("2000000"))
                .status(PricingRequest.PricingStatus.CALCULATED)
                .build();

        when(pricingRepository.findById(601L)).thenReturn(Optional.of(request));
        when(pricingRepository.saveAndFlush(any(PricingRequest.class)))
                .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("Row updated by concurrent transaction"));

        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
                () -> activities.recordQuoteApproval("601", "CUST-ACT-007"));
    }

    @Test
    @DisplayName("ISSUE-07: Should not overwrite terminal state EXPIRED when quote approval arrives late and never publish Kafka event")
    void testRecordQuoteApproval_TerminalStateSkipped() {
        PricingRequest request = PricingRequest.builder()
                .id(701L)
                .customerId("CUST-ACT-008")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("2000000"))
                .status(PricingRequest.PricingStatus.EXPIRED)
                .build();

        when(pricingRepository.findById(701L)).thenReturn(Optional.of(request));

        activities.recordQuoteApproval("701", "CUST-ACT-008");

        assertEquals(PricingRequest.PricingStatus.EXPIRED, request.getStatus());
        verify(pricingRepository, never()).save(request);
        verify(pricingRepository, never()).saveAndFlush(request);
        verify(pricingEventProducer, never()).publishPricingEvent(any());

        ArgumentCaptor<PricingAuditLog> captor = ArgumentCaptor.forClass(PricingAuditLog.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("SKIPPED_TERMINAL_STATE", captor.getValue().getOutcome());
    }

    @Test
    @DisplayName("ISSUE-07: Should not overwrite terminal state APPROVED when quote rejection arrives late and never publish Kafka event")
    void testRecordQuoteRejection_TerminalStateSkipped() {
        PricingRequest request = PricingRequest.builder()
                .id(801L)
                .customerId("CUST-ACT-009")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("2000000"))
                .status(PricingRequest.PricingStatus.APPROVED)
                .build();

        when(pricingRepository.findById(801L)).thenReturn(Optional.of(request));

        activities.recordQuoteRejection("801", "CUST-ACT-009", "Late rejection");

        assertEquals(PricingRequest.PricingStatus.APPROVED, request.getStatus());
        verify(pricingRepository, never()).save(request);
        verify(pricingRepository, never()).saveAndFlush(request);
        verify(pricingEventProducer, never()).publishPricingEvent(any());

        ArgumentCaptor<PricingAuditLog> captor = ArgumentCaptor.forClass(PricingAuditLog.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("SKIPPED_TERMINAL_STATE", captor.getValue().getOutcome());
    }

    @Test
    @DisplayName("ISSUE-07: Should not overwrite terminal state APPROVED when quote expiration timer fires and never publish Kafka event")
    void testRecordQuoteExpiration_TerminalStateSkipped() {
        PricingRequest request = PricingRequest.builder()
                .id(901L)
                .customerId("CUST-ACT-010")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("2000000"))
                .status(PricingRequest.PricingStatus.APPROVED)
                .build();

        when(pricingRepository.findById(901L)).thenReturn(Optional.of(request));

        activities.recordQuoteExpiration("901", "CUST-ACT-010");

        assertEquals(PricingRequest.PricingStatus.APPROVED, request.getStatus());
        verify(pricingRepository, never()).save(request);
        verify(pricingRepository, never()).saveAndFlush(request);
        verify(pricingEventProducer, never()).publishPricingEvent(any());

        ArgumentCaptor<PricingAuditLog> captor = ArgumentCaptor.forClass(PricingAuditLog.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("SKIPPED_TERMINAL_STATE", captor.getValue().getOutcome());
        assertEquals("RATE_EXPIRED", captor.getValue().getAction());
    }

    @Test
    @DisplayName("R1: Should not overwrite terminal state EXPIRED when quote rejection arrives late")
    void testRecordQuoteRejection_ExpiredTerminalStateSkipped() {
        PricingRequest request = PricingRequest.builder()
                .id(902L)
                .customerId("CUST-ACT-011")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("2000000"))
                .status(PricingRequest.PricingStatus.EXPIRED)
                .traceId("trace-expired-reject")
                .build();

        when(pricingRepository.findById(902L)).thenReturn(Optional.of(request));

        activities.recordQuoteRejection("902", "CUST-ACT-011", "Late rejection after expiry");

        assertEquals(PricingRequest.PricingStatus.EXPIRED, request.getStatus());
        assertNull(request.getErrorMessage());
        verify(pricingRepository, never()).save(any());
        verify(pricingRepository, never()).saveAndFlush(any());
        verify(pricingEventProducer, never()).publishPricingEvent(any());

        ArgumentCaptor<PricingAuditLog> captor = ArgumentCaptor.forClass(PricingAuditLog.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("SKIPPED_TERMINAL_STATE", captor.getValue().getOutcome());
        assertEquals("RATE_REJECTED", captor.getValue().getAction());
        assertEquals("trace-expired-reject", captor.getValue().getTraceId());
    }

    @Test
    @DisplayName("R1: Should handle duplicate recordQuoteRejection on already REJECTED quote idempotently")
    void testRecordQuoteRejection_SameStateRejectedSkipped() {
        PricingRequest request = PricingRequest.builder()
                .id(903L)
                .customerId("CUST-ACT-012")
                .productType("PERSONAL_LOAN")
                .loanAmount(new BigDecimal("300000"))
                .status(PricingRequest.PricingStatus.REJECTED)
                .errorMessage("Original rejection reason")
                .build();

        when(pricingRepository.findById(903L)).thenReturn(Optional.of(request));

        activities.recordQuoteRejection("903", "CUST-ACT-012", "Duplicate rejection");

        assertEquals(PricingRequest.PricingStatus.REJECTED, request.getStatus());
        assertEquals("Original rejection reason", request.getErrorMessage());
        verify(pricingRepository, never()).save(any());
        verify(pricingRepository, never()).saveAndFlush(any());
        verify(pricingEventProducer, never()).publishPricingEvent(any());

        ArgumentCaptor<PricingAuditLog> captor = ArgumentCaptor.forClass(PricingAuditLog.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("SKIPPED_TERMINAL_STATE", captor.getValue().getOutcome());
        assertEquals("RATE_REJECTED", captor.getValue().getAction());
    }

    @Test
    @DisplayName("R1: Should not overwrite terminal state REJECTED when quote expiration timer fires")
    void testRecordQuoteExpiration_RejectedTerminalStateSkipped() {
        PricingRequest request = PricingRequest.builder()
                .id(904L)
                .customerId("CUST-ACT-013")
                .productType("AUTO_LOAN")
                .loanAmount(new BigDecimal("800000"))
                .status(PricingRequest.PricingStatus.REJECTED)
                .traceId("trace-rejected-expire")
                .build();

        when(pricingRepository.findById(904L)).thenReturn(Optional.of(request));

        activities.recordQuoteExpiration("904", "CUST-ACT-013");

        assertEquals(PricingRequest.PricingStatus.REJECTED, request.getStatus());
        verify(pricingRepository, never()).save(any());
        verify(pricingRepository, never()).saveAndFlush(any());
        verify(pricingEventProducer, never()).publishPricingEvent(any());

        ArgumentCaptor<PricingAuditLog> captor = ArgumentCaptor.forClass(PricingAuditLog.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("SKIPPED_TERMINAL_STATE", captor.getValue().getOutcome());
        assertEquals("RATE_EXPIRED", captor.getValue().getAction());
        assertEquals("trace-rejected-expire", captor.getValue().getTraceId());
    }

    @Test
    @DisplayName("R1: Should handle duplicate recordQuoteExpiration on already EXPIRED quote idempotently")
    void testRecordQuoteExpiration_SameStateExpiredSkipped() {
        PricingRequest request = PricingRequest.builder()
                .id(905L)
                .customerId("CUST-ACT-014")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("2500000"))
                .status(PricingRequest.PricingStatus.EXPIRED)
                .build();

        when(pricingRepository.findById(905L)).thenReturn(Optional.of(request));

        activities.recordQuoteExpiration("905", "CUST-ACT-014");

        assertEquals(PricingRequest.PricingStatus.EXPIRED, request.getStatus());
        verify(pricingRepository, never()).save(any());
        verify(pricingRepository, never()).saveAndFlush(any());
        verify(pricingEventProducer, never()).publishPricingEvent(any());

        ArgumentCaptor<PricingAuditLog> captor = ArgumentCaptor.forClass(PricingAuditLog.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("SKIPPED_TERMINAL_STATE", captor.getValue().getOutcome());
        assertEquals("RATE_EXPIRED", captor.getValue().getAction());
    }

    @Test
    @DisplayName("R1: Should handle duplicate recordQuoteApproval on already APPROVED quote idempotently")
    void testRecordQuoteApproval_SameStateApprovedSkipped() {
        PricingRequest request = PricingRequest.builder()
                .id(906L)
                .customerId("CUST-ACT-015")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("2500000"))
                .status(PricingRequest.PricingStatus.APPROVED)
                .build();

        when(pricingRepository.findById(906L)).thenReturn(Optional.of(request));

        activities.recordQuoteApproval("906", "CUST-ACT-015");

        assertEquals(PricingRequest.PricingStatus.APPROVED, request.getStatus());
        verify(pricingRepository, never()).save(any());
        verify(pricingRepository, never()).saveAndFlush(any());
        verify(pricingEventProducer, never()).publishPricingEvent(any());

        ArgumentCaptor<PricingAuditLog> captor = ArgumentCaptor.forClass(PricingAuditLog.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("SKIPPED_TERMINAL_STATE", captor.getValue().getOutcome());
        assertEquals("RATE_APPROVED", captor.getValue().getAction());
    }

    @Test
    @DisplayName("R1: Should propagate OptimisticLockingFailureException on rejection and expiration save conflicts")
    void testRecordQuoteRejectionAndExpiration_OptimisticLockingFailure() {
        PricingRequest rejectCandidate = PricingRequest.builder()
                .id(907L)
                .customerId("CUST-ACT-016")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("2000000"))
                .status(PricingRequest.PricingStatus.CALCULATED)
                .build();
        when(pricingRepository.findById(907L)).thenReturn(Optional.of(rejectCandidate));
        when(pricingRepository.saveAndFlush(rejectCandidate))
                .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("Concurrent update"));

        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
                () -> activities.recordQuoteRejection("907", "CUST-ACT-016", "Conflict test"));

        PricingRequest expireCandidate = PricingRequest.builder()
                .id(908L)
                .customerId("CUST-ACT-017")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("2000000"))
                .status(PricingRequest.PricingStatus.CALCULATED)
                .build();
        when(pricingRepository.findById(908L)).thenReturn(Optional.of(expireCandidate));
        when(pricingRepository.saveAndFlush(expireCandidate))
                .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("Concurrent update"));

        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
                () -> activities.recordQuoteExpiration("908", "CUST-ACT-017"));
    }

    @Test
    @DisplayName("Should handle null CompletableFuture from PricingEventProducer safely without crashing")
    void testRecordQuoteApproval_NullPublishFutureHandledSafely() {
        PricingRequest request = PricingRequest.builder()
                .id(909L)
                .customerId("CUST-ACT-018")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("1500000"))
                .calculatedRate(new BigDecimal("8.50"))
                .emiAmount(new BigDecimal("14772.00"))
                .loanTenureMonths(180)
                .status(PricingRequest.PricingStatus.CALCULATED)
                .traceId("trace-null-future-test")
                .build();

        when(pricingRepository.findById(909L)).thenReturn(Optional.of(request));
        when(pricingRepository.saveAndFlush(any(PricingRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pricingEventProducer.publishPricingEvent(any())).thenReturn(null);

        assertDoesNotThrow(() -> activities.recordQuoteApproval("909", "CUST-ACT-018"));
        assertEquals(PricingRequest.PricingStatus.APPROVED, request.getStatus());
        verify(pricingEventProducer, times(1)).publishPricingEvent(any(LoanPricingEvent.class));
    }
}
