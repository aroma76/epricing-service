package com.bank.epricing.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Applies bounded lock_timeout and statement_timeout on PostgreSQL/YugabyteDB connections
 * so slow queries or row lock conflicts fail fast strictly below Temporal's 30s activity timeout.
 */
@Component
public class PostgresTimeoutBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(PostgresTimeoutBeanPostProcessor.class);

    private final long lockTimeoutMs;
    private final long statementTimeoutMs;

    public PostgresTimeoutBeanPostProcessor(
            @Value("${epricing.database.lock-timeout-ms:5000}") long lockTimeoutMs,
            @Value("${epricing.database.statement-timeout-ms:15000}") long statementTimeoutMs) {
        this.lockTimeoutMs = Math.max(1000L, lockTimeoutMs);
        this.statementTimeoutMs = Math.max(2000L, statementTimeoutMs);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof HikariDataSource hikari) {
            String jdbcUrl = hikari.getJdbcUrl();
            if (jdbcUrl != null && (jdbcUrl.startsWith("jdbc:postgresql:") || jdbcUrl.startsWith("jdbc:yugabytedb:"))) {
                String existing = hikari.getConnectionInitSql();
                String timeoutSql = String.format(
                        "SET lock_timeout = %d; SET statement_timeout = %d;",
                        lockTimeoutMs, statementTimeoutMs);
                hikari.setConnectionInitSql(existing != null && !existing.isBlank()
                        ? existing + "; " + timeoutSql
                        : timeoutSql);
                log.info("Configured PostgreSQL/YugabyteDB session fail-fast timeouts | lockTimeoutMs={} | statementTimeoutMs={}",
                        lockTimeoutMs, statementTimeoutMs);
            }
        }
        return bean;
    }
}
