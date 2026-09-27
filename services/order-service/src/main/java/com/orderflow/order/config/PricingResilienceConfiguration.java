package com.orderflow.order.config;

import com.orderflow.order.infrastructure.pricing.PricingRequestRejectedException;
import com.orderflow.order.infrastructure.pricing.PricingRetryPolicy;
import com.orderflow.order.infrastructure.pricing.PricingUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PricingResilienceProperties.class)
public class PricingResilienceConfiguration {

    @Bean
    public RetryRegistry pricingRetryRegistry(PricingResilienceProperties properties) {
        RetryConfig config = RetryConfig.custom()
            .maxAttempts(properties.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                properties.initialWait().toMillis(), properties.backoffMultiplier(), properties.jitterFactor()))
            .retryOnException(PricingRetryPolicy::isRetryable)
            .build();
        return RetryRegistry.of(config);
    }

    @Bean
    public Retry pricingRetry(RetryRegistry pricingRetryRegistry) {
        return pricingRetryRegistry.retry("pricing");
    }

    @Bean
    public CircuitBreakerRegistry pricingCircuitBreakerRegistry(PricingResilienceProperties properties) {
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
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public CircuitBreaker pricingCircuitBreaker(CircuitBreakerRegistry pricingCircuitBreakerRegistry) {
        return pricingCircuitBreakerRegistry.circuitBreaker("pricing");
    }

    // The tagged binders read registries, so the breaker and the retry come from one each.
    @Bean
    MeterBinder pricingCircuitBreakerMetrics(CircuitBreakerRegistry pricingCircuitBreakerRegistry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(pricingCircuitBreakerRegistry);
    }

    @Bean
    MeterBinder pricingRetryMetrics(RetryRegistry pricingRetryRegistry) {
        return TaggedRetryMetrics.ofRetryRegistry(pricingRetryRegistry);
    }
}
