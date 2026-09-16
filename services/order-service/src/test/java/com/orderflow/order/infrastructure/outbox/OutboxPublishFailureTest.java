package com.orderflow.order.infrastructure.outbox;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.errors.NotLeaderOrFollowerException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaProducerException;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublishFailureTest {

    @Test
    void recordTooLargeIsPermanent() {
        assertThat(OutboxPublishFailure.isPermanent(new ExecutionException(new RecordTooLargeException("too large")))).isTrue();
    }

    @Test
    void permanentKafkaErrorWrappedBySpringIsPermanent() {
        var wrapped = new KafkaProducerException(new ProducerRecord<>("topic", "key", "value"), "send failed",
            new SerializationException("bad value"));
        assertThat(OutboxPublishFailure.isPermanent(new ExecutionException(wrapped))).isTrue();
    }

    @Test
    void retriableKafkaErrorIsTransient() {
        assertThat(OutboxPublishFailure.isPermanent(new ExecutionException(new NotLeaderOrFollowerException("moving")))).isFalse();
    }

    @Test
    void acknowledgementTimeoutIsTransient() {
        assertThat(OutboxPublishFailure.isPermanent(new TimeoutException("no ack"))).isFalse();
    }

    @Test
    void interruptionIsTransient() {
        assertThat(OutboxPublishFailure.isPermanent(new InterruptedException("stopping"))).isFalse();
    }

    @Test
    void unknownFailureIsTransient() {
        assertThat(OutboxPublishFailure.isPermanent(new ExecutionException(new IllegalStateException("broker unavailable")))).isFalse();
    }

    @Test
    void descriptionNamesTheInnermostKafkaCause() {
        assertThat(OutboxPublishFailure.describe(new ExecutionException(new RecordTooLargeException("too large"))))
            .isEqualTo("RecordTooLargeException: too large");
    }

    @Test
    void descriptionIsTruncatedTo1000Characters() {
        assertThat(OutboxPublishFailure.describe(new IllegalStateException("x".repeat(5000)))).hasSize(1000);
    }
}
