package com.bank.epricing.service;

import com.bank.epricing.event.LoanPricingEvent;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

/**
 * Event Producer contract for publishing loan pricing domain events to Apache Kafka.
 */
public interface PricingEventProducer {

    /**
     * Publishes a LoanPricingEvent to the configured Kafka topic asynchronously.
     *
     * @param event The domain event to publish
     * @return CompletableFuture representing the asynchronous publish result
     */
    CompletableFuture<SendResult<String, Object>> publishPricingEvent(LoanPricingEvent event);

    /**
     * Returns whether Kafka publishing is actively enabled.
     */
    boolean isKafkaEnabled();

    /**
     * Returns the target Kafka topic name.
     */
    String getTopic();
}
