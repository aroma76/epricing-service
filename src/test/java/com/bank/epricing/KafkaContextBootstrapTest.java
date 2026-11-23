package com.bank.epricing;

import com.bank.epricing.config.KafkaProducerConfig;
import com.bank.epricing.service.KafkaPricingEventProducer;
import com.bank.epricing.service.PricingEventProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies full Spring Boot 3.3.4 ApplicationContext bootstrap with Kafka enabled:
 * - KafkaProducerConfig active
 * - ProducerFactory and KafkaTemplate beans registered
 * - PricingEventProducer wired and enabled
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "kafka.enabled=true",
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.kafka.template.default-topic=loan-pricing-events"
})
class KafkaContextBootstrapTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private KafkaProducerConfig kafkaProducerConfig;

    @Autowired(required = false)
    private ProducerFactory<String, Object> producerFactory;

    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired(required = false)
    private PricingEventProducer pricingEventProducer;

    @Test
    @DisplayName("Kafka infrastructure and producer beans are correctly wired when kafka.enabled is true")
    void testKafkaContextBootstrap() {
        assertNotNull(applicationContext, "ApplicationContext must be active");
        assertNotNull(kafkaProducerConfig, "KafkaProducerConfig bean must be loaded");
        assertNotNull(producerFactory, "ProducerFactory bean must be created");
        assertNotNull(kafkaTemplate, "KafkaTemplate bean must be created");
        assertNotNull(pricingEventProducer, "PricingEventProducer must be wired");

        assertTrue(pricingEventProducer instanceof KafkaPricingEventProducer);
        assertTrue(pricingEventProducer.isKafkaEnabled());
        assertEquals("loan-pricing-events", pricingEventProducer.getTopic());
    }
}
