package com.orderflow.inventory.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.OrderPlacedPayload;
import com.orderflow.inventory.reservation.domain.ReservationLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderPlacedProcessorTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();

    private final ReservationOutcomeService outcomes = mock(ReservationOutcomeService.class);
    private final OrderPlacedProcessor processor = new OrderPlacedProcessor(outcomes);

    @Test
    void reservesValidOrder() {
        processor.process(event(ORDER_ID.toString(), UUID.randomUUID(), "B2B",
            List.of(new OrderPlacedPayload.Line("CHAIR-001", 2))));

        verify(outcomes).reserve(EVENT_ID, ORDER_ID, List.of(new ReservationLine("CHAIR-001", 2)));
    }

    @Test
    void rejectsZeroQuantityWithItsSku() {
        processor.process(event(ORDER_ID.toString(), UUID.randomUUID(), "B2B",
            List.of(new OrderPlacedPayload.Line("CHAIR-001", 1), new OrderPlacedPayload.Line("CHAIR-005", 0))));

        verify(outcomes).rejectInvalid(EVENT_ID, ORDER_ID, List.of("CHAIR-005"));
        verify(outcomes, never()).reserve(any(), any(), anyList());
    }

    @Test
    void rejectsOverlongSkuTruncatedTo32Characters() {
        String sku = "S".repeat(40);

        processor.process(event(ORDER_ID.toString(), UUID.randomUUID(), "B2B",
            List.of(new OrderPlacedPayload.Line(sku, 1))));

        verify(outcomes).rejectInvalid(EVENT_ID, ORDER_ID, List.of("S".repeat(32)));
    }

    @Test
    void rejectsQuantityOverflowOfOneSku() {
        processor.process(event(ORDER_ID.toString(), UUID.randomUUID(), "B2B",
            List.of(new OrderPlacedPayload.Line("CHAIR-001", Integer.MAX_VALUE),
                new OrderPlacedPayload.Line("CHAIR-001", 1))));

        verify(outcomes).rejectInvalid(EVENT_ID, ORDER_ID, List.of("CHAIR-001"));
    }

    @Test
    void rejectsMissingCustomerWithoutSkus() {
        processor.process(event(ORDER_ID.toString(), null, "B2B",
            List.of(new OrderPlacedPayload.Line("CHAIR-001", 1))));

        verify(outcomes).rejectInvalid(EVENT_ID, ORDER_ID, List.of());
    }

    @Test
    void rejectsBlankSkuWithoutNamingIt() {
        processor.process(event(ORDER_ID.toString(), UUID.randomUUID(), "B2B",
            List.of(new OrderPlacedPayload.Line(" ", 1))));

        verify(outcomes).rejectInvalid(EVENT_ID, ORDER_ID, List.of());
    }

    @Test
    void unidentifiableOrderIsNotProcessed() {
        assertThatThrownBy(() -> processor.process(event(UUID.randomUUID().toString(), UUID.randomUUID(), "B2B",
            List.of(new OrderPlacedPayload.Line("CHAIR-001", 1)))))
            .isInstanceOf(IllegalArgumentException.class);
        verify(outcomes, never()).rejectInvalid(any(), any(), anyList());
    }

    private static EventEnvelope<OrderPlacedPayload> event(String aggregateId, UUID customerId, String customerType,
                                                          List<OrderPlacedPayload.Line> lines) {
        return new EventEnvelope<>(EVENT_ID, "OrderPlaced", 1, Instant.parse("2026-10-01T10:15:31Z"), aggregateId,
            new OrderPlacedPayload(ORDER_ID, customerId, customerType, lines));
    }
}
