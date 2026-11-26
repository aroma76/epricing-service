package com.bank.epricing.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables background scheduling for OutboxRelayService when outbox relaying is active.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "epricing.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayConfig {
}
