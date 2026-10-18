package com.orderflow.order.pricing.config;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PricingResilienceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PricingResilienceConfiguration.class)
        .withPropertyValues(
            "order.pricing.resilience.max-attempts=2",
            "order.pricing.resilience.initial-wait=100ms",
            "order.pricing.resilience.backoff-multiplier=2",
            "order.pricing.resilience.jitter-factor=0.2",
            "order.pricing.resilience.sliding-window-size=10",
            "order.pricing.resilience.minimum-number-of-calls=10",
            "order.pricing.resilience.failure-rate-threshold=50",
            "order.pricing.resilience.open-wait=10s",
            "order.pricing.resilience.half-open-permitted-calls=2");

    @Test
    void bindsSettingsAndCreatesPricingProtectionBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PricingResilienceProperties.class).openWait())
                .isEqualTo(Duration.ofSeconds(10));
            assertThat(context.getBean(Retry.class).getRetryConfig().getMaxAttempts()).isEqualTo(2);
            assertThat(context.getBean(CircuitBreaker.class).getCircuitBreakerConfig()
                .getSlidingWindowSize()).isEqualTo(10);
        });
    }

    @Test
    void publishesPricingBreakerAndRetryMetrics() {
        contextRunner.run(context -> {
            var registry = new SimpleMeterRegistry();
            context.getBeansOfType(MeterBinder.class).values().forEach(binder -> binder.bindTo(registry));

            assertThat(registry.find("resilience4j.circuitbreaker.state")
                .tags("name", "pricing", "state", "closed").gauge().value()).isEqualTo(1.0);
            assertThat(registry.find("resilience4j.retry.calls").tag("name", "pricing").meters()).isNotEmpty();
        });
    }
}
