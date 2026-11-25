package com.bank.epricing.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * Apache Kafka Producer Configuration for ePricing domain events.
 * Only loaded when kafka.enabled is true (default true).
 */
@Configuration
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaProducerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerConfig.class);

    private final KafkaProperties kafkaProperties;

    public KafkaProducerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    public NewTopic loanPricingEventsTopic(
            @Value("${spring.kafka.template.default-topic:${kafka.topic:loan-pricing-events}}") String topicName) {
        log.info("Registering Kafka NewTopic bean | topicName={}", topicName);
        return TopicBuilder.name(topicName)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = kafkaProperties.buildProducerProperties(null);
        configProps.putIfAbsent(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.putIfAbsent(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.putIfAbsent(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.putIfAbsent(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        configProps.putIfAbsent(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        log.info("Initializing Kafka ProducerFactory | bootstrapServers={} | acks={} | retries={}",
                configProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG),
                configProps.get(ProducerConfig.ACKS_CONFIG),
                configProps.get(ProducerConfig.RETRIES_CONFIG));

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory,
                                                       @Value("${spring.kafka.template.default-topic:${kafka.topic:loan-pricing-events}}") String defaultTopic) {
        log.info("Initializing KafkaTemplate bean | defaultTopic={}", defaultTopic);
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(producerFactory);
        template.setDefaultTopic(defaultTopic);
        return template;
    }
}
