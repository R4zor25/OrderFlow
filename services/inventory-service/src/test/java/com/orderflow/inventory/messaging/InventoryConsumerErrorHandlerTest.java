package com.orderflow.inventory.messaging;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryConsumerErrorHandlerTest {

    private static final String SOURCE = "orders.order-events.v1";
    private static final String DLT = SOURCE + ".inventory-service.DLT";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DefaultErrorHandler handler = new InventoryMessagingConfiguration.EnabledMessagingConfiguration()
        .inventoryErrorHandler(kafka, new InventoryConsumerErrorProperties(Duration.ofSeconds(1), 2.0,
            Duration.ofSeconds(30), Duration.ofSeconds(10)), registry);

    @Test
    void registersTheDeadLetterCounterBeforeTheFirstDeadLetter() {
        assertThat(deadLetters()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLettersToAnExistingPartitionAndCountsTheConfirmedSend() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        Consumer<String, String> consumer = mock(Consumer.class);
        when(consumer.partitionsFor(eq(DLT), any(Duration.class))).thenReturn(List.of(
            new PartitionInfo(DLT, 0, null, null, null), new PartitionInfo(DLT, 1, null, null, null),
            new PartitionInfo(DLT, 2, null, null, null)));

        handler.handleOne(new IllegalArgumentException("invalid"), new ConsumerRecord<>(SOURCE, 5, 0, "k", "v"),
            consumer, mock(MessageListenerContainer.class));

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo(DLT);
        // The DLT has no partition 5, so the recoverer must see the consumer and let the producer choose.
        assertThat(sent.getValue().partition()).isNull();
        assertThat(deadLetters()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotCountAFailedDeadLetterSend() {
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        catchThrowable(() -> handler.handleOne(new IllegalArgumentException("invalid"),
            new ConsumerRecord<>(SOURCE, 0, 0, "k", "v"), mock(Consumer.class), mock(MessageListenerContainer.class)));

        assertThat(deadLetters()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLettersAConstraintViolationWithoutRetrying() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        boolean handled = handler.handleOne(new DataIntegrityViolationException("value too long"),
            new ConsumerRecord<>(SOURCE, 0, 0, "k", "v"), mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(handled).isTrue();
        verify(kafka).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsRetryingATransientDatabaseFailure() {
        boolean handled = handler.handleOne(new CannotCreateTransactionException("database down"),
            new ConsumerRecord<>(SOURCE, 0, 0, "k", "v"), mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(handled).isFalse();
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    private double deadLetters() {
        return registry.get("messaging.dead.letters").tag("topic", DLT).counter().count();
    }
}
