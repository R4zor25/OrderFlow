package com.orderflow.inventory.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("inventory.retention")
public record RetentionProperties(@NotBlank String cron, @NotNull Duration outbox,
                                  @NotNull Duration processedEvents, @NotNull Duration cancelledOrders,
                                  @Min(1) int batchSize) {

    @AssertTrue(message = "retention periods must be positive")
    boolean isPositive() {
        // @NotNull reports missing values; this check must not fail on them first.
        return (outbox == null || outbox.isPositive()) && (processedEvents == null || processedEvents.isPositive())
            && (cancelledOrders == null || cancelledOrders.isPositive());
    }
}
