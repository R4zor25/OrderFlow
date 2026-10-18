package com.orderflow.order.retention;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionPropertiesTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final Duration DAYS_14 = Duration.ofDays(14);

    @Test
    void acceptsPositivePeriods() {
        assertThat(VALIDATOR.validate(properties(DAYS_14, DAYS_14))).isEmpty();
    }

    @Test
    void rejectsZeroOrNegativePeriods() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofDays(-1))) {
            assertThat(VALIDATOR.validate(properties(invalid, DAYS_14))).as("outbox %s", invalid).isNotEmpty();
            assertThat(VALIDATOR.validate(properties(DAYS_14, invalid))).as("processed events %s", invalid).isNotEmpty();
        }
    }

    @Test
    void reportsAMissingPeriodOnlyAsMissing() {
        assertThat(VALIDATOR.validate(properties(null, DAYS_14)))
            .extracting(violation -> violation.getPropertyPath().toString())
            .containsExactly("outbox");
    }

    private static RetentionProperties properties(Duration outbox, Duration processedEvents) {
        return new RetentionProperties("0 0 3 * * *", outbox, processedEvents, 1000);
    }
}
