package com.bank.epricing.service;

import com.bank.epricing.event.LoanPricingEvent;
import io.opentelemetry.api.trace.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PricingEventProducerTest {

    private KafkaOperations<String, Object> kafkaOperations;
    private Tracer tracer;
    private Span span;
    private SpanBuilder spanBuilder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaOperations = mock(KafkaOperations.class);
        tracer = mock(Tracer.class);
        span = mock(Span.class);
        spanBuilder = mock(SpanBuilder.class);

        SpanContext spanContext = mock(SpanContext.class);
        when(spanContext.isValid()).thenReturn(true);
        when(spanContext.getTraceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(spanContext.getSpanId()).thenReturn("00f067aa0ba902b7");
        TraceFlags traceFlags = mock(TraceFlags.class);
        when(traceFlags.asHex()).thenReturn("01");
        when(spanContext.getTraceFlags()).thenReturn(traceFlags);
        when(spanContext.isSampled()).thenReturn(true);
        when(span.getSpanContext()).thenReturn(spanContext);

        when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
    }

    private LoanPricingEvent createSampleEvent() {
        return LoanPricingEvent.builder()
                .requestId(101L)
                .customerId("CUST001234")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("5000000.00"))
                .interestRate(new BigDecimal("8.25"))
                .emiAmount(new BigDecimal("42567.89"))
                .loanTenureMonths(240)
                .status("CALCULATED")
                .timestamp(LocalDateTime.now())
                .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
                .build();
    }

    @Test
    @DisplayName("Should publish event successfully to Kafka with traceparent header and record metadata")
    @SuppressWarnings("unchecked")
    void testPublishPricingEvent_Success() throws Exception {
        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaOperations, tracer, true, "loan-pricing-events"
        );

        assertTrue(producer.isKafkaEnabled());
        assertEquals("loan-pricing-events", producer.getTopic());

        LoanPricingEvent event = createSampleEvent();
        ProducerRecord<String, Object> record = new ProducerRecord<>("loan-pricing-events", event.getCustomerId(), event);
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("loan-pricing-events", 0), 0L, 0, 0L, 0, 0
        );
        SendResult<String, Object> sendResult = new SendResult<>(record, metadata);
        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(future);

        CompletableFuture<SendResult<String, Object>> resultFuture = producer.publishPricingEvent(event);

        assertNotNull(resultFuture);
        SendResult<String, Object> actualResult = resultFuture.get();
        assertEquals(sendResult, actualResult);

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations, times(1)).send(captor.capture());

        ProducerRecord<String, Object> captured = captor.getValue();
        assertEquals("loan-pricing-events", captured.topic());
        assertEquals("CUST001234", captured.key());
        assertEquals(event, captured.value());
        assertNotNull(captured.headers().lastHeader("traceparent"));
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                new String(captured.headers().lastHeader("traceparent").value()));

        verify(spanBuilder).setAttribute("messaging.kafka.message.key", "CUST****1234");
        verify(spanBuilder).setAttribute("customer.id.masked", "CUST****1234");
        verify(span).setStatus(StatusCode.OK);
        verify(span).end();
    }

    @Test
    @DisplayName("Should safely handle SendResult with null RecordMetadata without throwing NPE")
    @SuppressWarnings("unchecked")
    void testPublishPricingEvent_NullRecordMetadata() throws Exception {
        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaOperations, tracer, true, "loan-pricing-events"
        );

        LoanPricingEvent event = createSampleEvent();
        ProducerRecord<String, Object> record = new ProducerRecord<>("loan-pricing-events", event.getCustomerId(), event);
        SendResult<String, Object> sendResult = new SendResult<>(record, null);
        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(future);

        CompletableFuture<SendResult<String, Object>> resultFuture = producer.publishPricingEvent(event);

        assertNotNull(resultFuture);
        assertEquals(sendResult, resultFuture.get());
        verify(span).setStatus(StatusCode.OK);
        verify(span).end();
    }

    @Test
    @DisplayName("Should handle Kafka publish failure callback cleanly and record span exception")
    @SuppressWarnings("unchecked")
    void testPublishPricingEvent_FailureCallback() {
        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaOperations, tracer, true, "loan-pricing-events"
        );

        LoanPricingEvent event = createSampleEvent();
        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker connection timeout"));

        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        CompletableFuture<SendResult<String, Object>> resultFuture = producer.publishPricingEvent(event);

        assertNotNull(resultFuture);
        assertTrue(resultFuture.isCompletedExceptionally());

        verify(span).setStatus(eq(StatusCode.ERROR), anyString());
        verify(span).recordException(any(Throwable.class));
        verify(span).end();
    }

    @Test
    @DisplayName("Should skip Kafka publish when kafka.enabled is false")
    void testPublishPricingEvent_Disabled() {
        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaOperations, tracer, false, "loan-pricing-events"
        );

        assertFalse(producer.isKafkaEnabled());

        LoanPricingEvent event = createSampleEvent();
        CompletableFuture<SendResult<String, Object>> resultFuture = producer.publishPricingEvent(event);

        assertNotNull(resultFuture);
        assertTrue(resultFuture.isDone());
        verifyNoInteractions(kafkaOperations);
        verifyNoInteractions(tracer);
    }

    @Test
    @DisplayName("Should skip Kafka publish when kafkaTemplate is null")
    void testPublishPricingEvent_NullTemplate() {
        PricingEventProducer producer = new KafkaPricingEventProducer(
                null, tracer, true, "loan-pricing-events"
        );

        LoanPricingEvent event = createSampleEvent();
        CompletableFuture<SendResult<String, Object>> resultFuture = producer.publishPricingEvent(event);

        assertNotNull(resultFuture);
        assertTrue(resultFuture.isDone());
        verifyNoInteractions(tracer);
    }

    @Test
    @DisplayName("Should return completed future when event is null")
    void testPublishPricingEvent_NullEvent() {
        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaOperations, tracer, true, "loan-pricing-events"
        );

        CompletableFuture<SendResult<String, Object>> resultFuture = producer.publishPricingEvent(null);

        assertNotNull(resultFuture);
        assertTrue(resultFuture.isDone());
        verifyNoInteractions(kafkaOperations);
    }

    @Test
    @DisplayName("Should catch immediate send exception and return exceptionally completed future")
    @SuppressWarnings("unchecked")
    void testPublishPricingEvent_ImmediateException() {
        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaOperations, tracer, true, "loan-pricing-events"
        );

        LoanPricingEvent event = createSampleEvent();
        when(kafkaOperations.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("Buffer pool out of memory"));

        CompletableFuture<SendResult<String, Object>> resultFuture = producer.publishPricingEvent(event);

        assertNotNull(resultFuture);
        assertTrue(resultFuture.isCompletedExceptionally());
        assertThrows(ExecutionException.class, resultFuture::get);

        verify(span).setStatus(eq(StatusCode.ERROR), eq("Buffer pool out of memory"));
        verify(span).end();
    }

    @Test
    @DisplayName("Should publish cleanly when Tracer is null without throwing NPE")
    @SuppressWarnings("unchecked")
    void testPublishPricingEvent_NullTracer() throws Exception {
        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaOperations, null, true, "loan-pricing-events"
        );

        LoanPricingEvent event = createSampleEvent();
        ProducerRecord<String, Object> record = new ProducerRecord<>("loan-pricing-events", event.getCustomerId(), event);
        SendResult<String, Object> sendResult = new SendResult<>(record, null);
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        CompletableFuture<SendResult<String, Object>> resultFuture = producer.publishPricingEvent(event);
        assertNotNull(resultFuture);
        assertEquals(sendResult, resultFuture.get());
        verify(kafkaOperations, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("Should handle null customerId in event without throwing NPE")
    @SuppressWarnings("unchecked")
    void testPublishPricingEvent_NullCustomerId() throws Exception {
        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaOperations, tracer, true, "loan-pricing-events"
        );

        LoanPricingEvent event = LoanPricingEvent.builder()
                .requestId(999L)
                .customerId(null)
                .productType("PERSONAL_LOAN")
                .loanAmount(new BigDecimal("100000"))
                .interestRate(new BigDecimal("11.5"))
                .emiAmount(new BigDecimal("5000"))
                .status("CALCULATED")
                .build();

        ProducerRecord<String, Object> record = new ProducerRecord<>("loan-pricing-events", null, event);
        SendResult<String, Object> sendResult = new SendResult<>(record, null);
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        CompletableFuture<SendResult<String, Object>> resultFuture = producer.publishPricingEvent(event);
        assertNotNull(resultFuture);
        assertEquals(sendResult, resultFuture.get());
        verify(spanBuilder).setAttribute("messaging.kafka.message.key", "");
        verify(spanBuilder).setAttribute("customer.id.masked", "");
    }

    @Test
    @DisplayName("ISSUE-03: Should increment epricing.kafka.publish.success metric counter when Kafka send succeeds")
    @SuppressWarnings("unchecked")
    void testPublishPricingEvent_IncrementsSuccessMetric() throws Exception {
        io.micrometer.core.instrument.MeterRegistry meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaOperations, tracer, meterRegistry, true, "loan-pricing-events"
        );

        LoanPricingEvent event = createSampleEvent();
        ProducerRecord<String, Object> record = new ProducerRecord<>("loan-pricing-events", event.getCustomerId(), event);
        SendResult<String, Object> sendResult = new SendResult<>(record, null);
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        producer.publishPricingEvent(event).get();

        assertEquals(1.0, meterRegistry.get("epricing.kafka.publish.success")
                .tag("topic", "loan-pricing-events").counter().count());
    }

    @Test
    @DisplayName("ISSUE-03: Should increment epricing.kafka.publish.failed metric counter when Kafka send fails")
    @SuppressWarnings("unchecked")
    void testPublishPricingEvent_IncrementsFailureMetric() {
        io.micrometer.core.instrument.MeterRegistry meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaOperations, tracer, meterRegistry, true, "loan-pricing-events"
        );

        LoanPricingEvent event = createSampleEvent();
        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Broker disconnected"));
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        producer.publishPricingEvent(event);

        assertEquals(1.0, meterRegistry.get("epricing.kafka.publish.failed")
                .tag("topic", "loan-pricing-events").counter().count());
    }
}
