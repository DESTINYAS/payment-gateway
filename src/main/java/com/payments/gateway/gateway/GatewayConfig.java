package com.payments.gateway.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Bean
    public RetryPolicy gatewayRetryPolicy(
            @Value("${payments.gateway.max-attempts}") int maxAttempts,
            @Value("${payments.gateway.initial-backoff-ms}") long initialBackoffMs,
            @Value("${payments.gateway.backoff-multiplier}") double multiplier,
            @Value("${payments.gateway.max-backoff-ms}") long maxBackoffMs) {
        return RetryPolicy.of(maxAttempts, initialBackoffMs, multiplier, maxBackoffMs);
    }
}
