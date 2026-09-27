package com.orderflow.order.config;

import java.time.Duration;

import com.orderflow.order.domain.InvalidOrderStateException;
import com.orderflow.order.domain.OrderNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrderConsumerErrorHandlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void deterministicFailuresAreNotRetried() {
        var handler = new OrderMessagingConfiguration.EnabledMessagingConfiguration().orderConsumerErrorHandler(
            mock(KafkaTemplate.class),
            new OrderConsumerErrorProperties(Duration.ofSeconds(1), 3, Duration.ofSeconds(10)),
            new SimpleMeterRegistry());

        assertThat(handler.removeClassification(OrderNotFoundException.class)).isFalse();
        assertThat(handler.removeClassification(InvalidOrderStateException.class)).isFalse();
    }
}
