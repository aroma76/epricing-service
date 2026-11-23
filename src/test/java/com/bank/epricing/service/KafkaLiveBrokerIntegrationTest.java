package com.bank.epricing.service;

import com.bank.epricing.event.LoanPricingEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test verifying event publication and consumer roundtrip
 * against the live Apache Kafka KRaft broker on localhost:9092.
 */
class KafkaLiveBrokerIntegrationTest {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String TOPIC = "loan-pricing-events";

    @BeforeAll
    static void checkBrokerAvailable() {
        boolean brokerAvailable = false;
        try (Socket socket = new Socket("localhost", 9092)) {
            brokerAvailable = socket.isConnected();
        } catch (IOException ignored) {
        }
        Assumptions.assumeTrue(brokerAvailable, "Skipping live Kafka test: localhost:9092 is not reachable");
    }

    @Test
    @DisplayName("Should publish LoanPricingEvent to live Kafka broker and consume it with matching attributes")
    void testLivePublishAndConsumeRoundtrip() throws Exception {
        // Setup Producer
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerProps.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        DefaultKafkaProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(producerProps);
        KafkaTemplate<String, Object> kafkaTemplate = new KafkaTemplate<>(producerFactory);
        Tracer tracer = OpenTelemetry.noop().getTracer("test");

        PricingEventProducer producer = new KafkaPricingEventProducer(
                kafkaTemplate, tracer, true, TOPIC
        );

        // Setup Consumer
        String consumerGroupId = "test-group-" + UUID.randomUUID();
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        consumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, LoanPricingEvent.class.getName());

        try (KafkaConsumer<String, LoanPricingEvent> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(Collections.singletonList(TOPIC));

            Long uniqueRequestId = System.currentTimeMillis();
            String testTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
            LoanPricingEvent event = LoanPricingEvent.builder()
                    .requestId(uniqueRequestId)
                    .customerId("CUST-LIVE-001")
                    .productType("HOME_LOAN")
                    .loanAmount(new BigDecimal("7500000.00"))
                    .interestRate(new BigDecimal("8.35"))
                    .emiAmount(new BigDecimal("64350.50"))
                    .loanTenureMonths(240)
                    .totalPayableAmount(new BigDecimal("15444120.00"))
                    .totalInterestPayable(new BigDecimal("7944120.00"))
                    .riskCategory("LOW")
                    .status("CALCULATED")
                    .timestamp(LocalDateTime.now())
                    .traceId(testTraceId)
                    .build();

            // Publish event
            CompletableFuture<SendResult<String, Object>> future = producer.publishPricingEvent(event);
            SendResult<String, Object> sendResult = future.get(10, TimeUnit.SECONDS);

            assertNotNull(sendResult);
            assertNotNull(sendResult.getRecordMetadata());
            assertEquals(TOPIC, sendResult.getRecordMetadata().topic());
            assertTrue(sendResult.getRecordMetadata().partition() >= 0);
            assertTrue(sendResult.getRecordMetadata().offset() >= 0);

            // Poll and assert consumer receives matching event
            boolean found = false;
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline && !found) {
                ConsumerRecords<String, LoanPricingEvent> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, LoanPricingEvent> record : records) {
                    if (record.value() != null && uniqueRequestId.equals(record.value().getRequestId())) {
                        LoanPricingEvent received = record.value();
                        assertEquals("CUST-LIVE-001", received.getCustomerId());
                        assertEquals("HOME_LOAN", received.getProductType());
                        assertEquals(new BigDecimal("7500000.00"), received.getLoanAmount());
                        assertEquals(new BigDecimal("8.35"), received.getInterestRate());
                        assertEquals(new BigDecimal("64350.50"), received.getEmiAmount());
                        assertEquals(240, received.getLoanTenureMonths());
                        assertEquals("CALCULATED", received.getStatus());
                        assertEquals(testTraceId, received.getTraceId());
                        found = true;
                        break;
                    }
                }
            }

            assertTrue(found, "Live Kafka broker consumer did not receive published event within deadline");
        } finally {
            producerFactory.destroy();
        }
    }
}
