package com.orderflow.order.pricing.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order.pricing.resilience")
public record PricingResilienceProperties(
    int maxAttempts,
    Duration initialWait,
    double backoffMultiplier,
    double jitterFactor,
    int slidingWindowSize,
    int minimumNumberOfCalls,
    float failureRateThreshold,
    Duration openWait,
    int halfOpenPermittedCalls
) {

    public PricingResilienceProperties {
        if (maxAttempts < 1 || initialWait == null || initialWait.isNegative() || initialWait.isZero()
            || backoffMultiplier < 1 || jitterFactor < 0 || jitterFactor >= 1
            || slidingWindowSize < 1 || minimumNumberOfCalls < 1
            || minimumNumberOfCalls > slidingWindowSize
            || failureRateThreshold <= 0 || failureRateThreshold > 100
            || openWait == null || openWait.isNegative() || openWait.isZero()
            || halfOpenPermittedCalls < 1) {
            throw new IllegalArgumentException("Invalid pricing resilience configuration");
        }
    }
}
