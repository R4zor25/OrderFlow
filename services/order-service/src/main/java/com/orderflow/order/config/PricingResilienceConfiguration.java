package com.orderflow.order.config;

import com.orderflow.order.infrastructure.pricing.PricingRequestRejectedException;
import com.orderflow.order.infrastructure.pricing.PricingRetryPolicy;
import com.orderflow.order.infrastructure.pricing.PricingUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PricingResilienceProperties.class)
public class PricingResilienceConfiguration {

    @Bean
    public Retry pricingRetry(PricingResilienceProperties properties) {
        RetryConfig config = RetryConfig.custom()
            .maxAttempts(properties.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                properties.initialWait().toMillis(), properties.backoffMultiplier(), properties.jitterFactor()))
            .retryOnException(PricingRetryPolicy::isRetryable)
            .build();
        return Retry.of("pricing", config);
    }

    @Bean
    public CircuitBreaker pricingCircuitBreaker(PricingResilienceProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(properties.slidingWindowSize())
            .minimumNumberOfCalls(properties.minimumNumberOfCalls())
            .failureRateThreshold(properties.failureRateThreshold())
            .waitDurationInOpenState(properties.openWait())
            .permittedNumberOfCallsInHalfOpenState(properties.halfOpenPermittedCalls())
            .recordException(error -> error instanceof PricingUnavailableException)
            .ignoreExceptions(PricingRequestRejectedException.class)
            .build();
        return CircuitBreaker.of("pricing", config);
    }
}
