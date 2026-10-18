package com.orderflow.inventory.messaging;

import java.util.List;
import java.util.UUID;

import com.orderflow.inventory.reservation.application.OrderCancellationService;
import com.orderflow.inventory.reservation.application.OrderPlacedProcessor;
import com.orderflow.inventory.reservation.application.ReservationOutcomeService;
import com.orderflow.inventory.reservation.domain.ReservationLine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderEventListenerTest {

    private static final String TOPIC = "orders.order-events.v1";
    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String ORDER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String OTHER_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String PLACED_PAYLOAD = """
        {"orderId":"%s","customerId":"33333333-3333-4333-8333-333333333333","customerType":"B2B",
         "lines":[{"sku":"CHAIR-001","quantity":2}]}""";
    private static final String CANCELLED_PAYLOAD = """
        {"orderId":"%s","customerId":"33333333-3333-4333-8333-333333333333",
         "customerEmail":"standard@example.test","previousStatus":"CONFIRMED"}""";

    private final ReservationOutcomeService outcomes = mock(ReservationOutcomeService.class);
    private final OrderCancellationService cancellation = mock(OrderCancellationService.class);
    private final OrderEventListener listener = new OrderEventListener(
        JsonMapper.builder().findAndAddModules().build(), new OrderPlacedProcessor(outcomes), cancellation);

    @Test
    void rejectsMessageWithoutEventType() {
        var record = new ConsumerRecord<>(TOPIC, 0, 0L, ORDER_ID, "{\"payload\":{}}");

        assertThatThrownBy(() -> listener.onMessage(record))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipsUnknownEventType() {
        var record = new ConsumerRecord<>(TOPIC, 0, 0L, ORDER_ID, "{\"eventType\":\"OrderShipped\",\"eventVersion\":1}");

        listener.onMessage(record);

        verifyNoInteractions(outcomes, cancellation);
    }

    @Test
    void skipsNewerOrderEventVersion() {
        var record = new ConsumerRecord<>(TOPIC, 0, 0L, ORDER_ID, "{\"eventType\":\"OrderPlaced\",\"eventVersion\":2}");

        listener.onMessage(record);

        verifyNoInteractions(outcomes, cancellation);
    }

    @Test
    void leavesKnownOrderEventsForTheirOwnSteps() {
        for (String type : new String[]{"OrderConfirmed", "OrderRejected"}) {
            var record = new ConsumerRecord<>(TOPIC, 0, 0L, ORDER_ID, "{\"eventType\":\"" + type + "\"}");

            listener.onMessage(record);
        }

        verifyNoInteractions(outcomes, cancellation);
    }

    @Test
    void reservesPlacedOrder() {
        listener.onMessage(record(ORDER_ID, envelope("OrderPlaced", ORDER_ID, PLACED_PAYLOAD.formatted(ORDER_ID))));

        verify(outcomes).reserve(EVENT_ID, UUID.fromString(ORDER_ID), List.of(new ReservationLine("CHAIR-001", 2)));
    }

    @Test
    void releasesCancelledOrderWithOnlyItsOrderId() {
        listener.onMessage(record(ORDER_ID, envelope("OrderCancelled", ORDER_ID, "{\"orderId\":\"" + ORDER_ID + "\"}")));

        verify(cancellation).release(EVENT_ID, UUID.fromString(ORDER_ID));
    }

    @Test
    void rejectsOrderEventWhoseOrderDoesNotMatchTheAggregate() {
        var placed = record(ORDER_ID, envelope("OrderPlaced", ORDER_ID, PLACED_PAYLOAD.formatted(OTHER_ID)));
        var cancelled = record(ORDER_ID, envelope("OrderCancelled", ORDER_ID, CANCELLED_PAYLOAD.formatted(OTHER_ID)));

        assertThatThrownBy(() -> listener.onMessage(placed))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Unidentifiable OrderPlaced event");
        assertThatThrownBy(() -> listener.onMessage(cancelled))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid OrderCancelled event");
        verifyNoInteractions(outcomes, cancellation);
    }

    @Test
    void rejectsOrderEventWithoutAnOrderId() {
        var placed = record(ORDER_ID, envelope("OrderPlaced", ORDER_ID,
            PLACED_PAYLOAD.formatted(ORDER_ID).replace("\"orderId\":\"" + ORDER_ID + "\",", "")));
        var cancelled = record(ORDER_ID, envelope("OrderCancelled", ORDER_ID,
            CANCELLED_PAYLOAD.formatted(ORDER_ID).replace("\"orderId\":\"" + ORDER_ID + "\",", "")));

        assertThatThrownBy(() -> listener.onMessage(placed))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Unidentifiable OrderPlaced event");
        assertThatThrownBy(() -> listener.onMessage(cancelled))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid OrderCancelled event");
        verifyNoInteractions(outcomes, cancellation);
    }

    @Test
    void rejectsOrderEventWhoseKeyDoesNotMatchTheAggregate() {
        var placed = record(OTHER_ID, envelope("OrderPlaced", ORDER_ID, PLACED_PAYLOAD.formatted(ORDER_ID)));
        var cancelled = record(OTHER_ID, envelope("OrderCancelled", ORDER_ID, CANCELLED_PAYLOAD.formatted(ORDER_ID)));

        assertThatThrownBy(() -> listener.onMessage(placed))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Kafka key does not match order aggregate ID");
        assertThatThrownBy(() -> listener.onMessage(cancelled))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Kafka key does not match order aggregate ID");
        verifyNoInteractions(outcomes, cancellation);
    }

    // Both exception types are on the error handler's not-retryable list, so such an event is dead-lettered at once.
    @Test
    void rejectsOrderEventWithoutAggregateIdOrPayloadWithoutRetrying() {
        var withoutAggregate = List.of(
            envelope("OrderPlaced", null, PLACED_PAYLOAD.formatted(ORDER_ID)),
            envelope("OrderCancelled", null, CANCELLED_PAYLOAD.formatted(ORDER_ID)));
        var withoutPayload = List.of(envelope("OrderPlaced", ORDER_ID, null),
            envelope("OrderCancelled", ORDER_ID, null));

        for (String json : withoutAggregate) {
            assertThatThrownBy(() -> listener.onMessage(record(ORDER_ID, json)))
                .isInstanceOfAny(JacksonException.class, IllegalArgumentException.class);
        }
        for (String json : withoutPayload) {
            assertThatThrownBy(() -> listener.onMessage(record(ORDER_ID, json)))
                .isInstanceOfAny(JacksonException.class, IllegalArgumentException.class);
        }
        verifyNoInteractions(outcomes, cancellation);
    }

    private static ConsumerRecord<String, String> record(String key, String json) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, key, json);
    }

    private static String envelope(String type, String aggregateId, String payload) {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"" + type + "\",\"eventVersion\":1,"
            + "\"occurredAt\":\"2026-10-01T10:15:31Z\""
            + (aggregateId == null ? "" : ",\"aggregateId\":\"" + aggregateId + "\"")
            + (payload == null ? "" : ",\"payload\":" + payload) + "}";
    }
}
