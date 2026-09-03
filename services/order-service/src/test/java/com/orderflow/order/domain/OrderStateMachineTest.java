package com.orderflow.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTest {

    private static final Instant CREATED = Instant.parse("2026-09-25T09:00:00Z");
    private static final Instant PREPARED = Instant.parse("2026-09-25T09:05:00Z");
    private static final Instant CHANGED = Instant.parse("2026-09-25T09:10:00Z");

    private enum Action { CONFIRM, REJECT, CANCEL }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @MethodSource("validTransitions")
    void allowsDefinedTransitions(OrderStatus start, Action action, OrderStatus expected) {
        Order order = orderIn(start);

        apply(order, action);

        assertThat(order.getStatus()).isEqualTo(expected);
        assertThat(order.getUpdatedAt()).isEqualTo(CHANGED);
        assertThat(order.getRejectionReason())
            .isEqualTo(expected == OrderStatus.REJECTED ? "No stock" : null);
    }

    static Stream<Arguments> validTransitions() {
        return Stream.of(
            Arguments.of(OrderStatus.PENDING, Action.CONFIRM, OrderStatus.CONFIRMED),
            Arguments.of(OrderStatus.PENDING, Action.REJECT, OrderStatus.REJECTED),
            Arguments.of(OrderStatus.PENDING, Action.CANCEL, OrderStatus.CANCELLED),
            Arguments.of(OrderStatus.CONFIRMED, Action.CANCEL, OrderStatus.CANCELLED)
        );
    }

    @ParameterizedTest(name = "{0} + {1} is invalid")
    @MethodSource("invalidTransitions")
    void rejectsUndefinedTransitionsWithoutChangingTheOrder(OrderStatus start, Action action) {
        Order order = orderIn(start);
        Instant previousUpdate = order.getUpdatedAt();
        String previousReason = order.getRejectionReason();

        assertThatThrownBy(() -> apply(order, action))
            .isInstanceOf(InvalidOrderStateException.class);

        assertThat(order.getStatus()).isEqualTo(start);
        assertThat(order.getUpdatedAt()).isEqualTo(previousUpdate);
        assertThat(order.getRejectionReason()).isEqualTo(previousReason);
    }

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
            Arguments.of(OrderStatus.CONFIRMED, Action.CONFIRM),
            Arguments.of(OrderStatus.CONFIRMED, Action.REJECT),
            Arguments.of(OrderStatus.REJECTED, Action.CONFIRM),
            Arguments.of(OrderStatus.REJECTED, Action.REJECT),
            Arguments.of(OrderStatus.REJECTED, Action.CANCEL),
            Arguments.of(OrderStatus.CANCELLED, Action.CONFIRM),
            Arguments.of(OrderStatus.CANCELLED, Action.REJECT),
            Arguments.of(OrderStatus.CANCELLED, Action.CANCEL)
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void rejectsMissingRejectionReason(String reason) {
        Order order = newPendingOrder();

        assertThatThrownBy(() -> order.reject(reason, CHANGED))
            .isInstanceOf(InvalidOrderException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getUpdatedAt()).isEqualTo(CREATED);
        assertThat(order.getRejectionReason()).isNull();
    }

    @Test
    void rejectsReasonLongerThanDatabaseColumn() {
        Order order = newPendingOrder();
        assertThatThrownBy(() -> order.reject("a".repeat(501), CHANGED))
            .isInstanceOf(InvalidOrderException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void storesTrimmedRejectionReasonAtColumnLimit() {
        Order order = newPendingOrder();

        order.reject("  " + "a".repeat(500) + "  ", CHANGED);

        assertThat(order.getRejectionReason()).hasSize(500);
        assertThat(order.getRejectionReason()).doesNotStartWith(" ");
    }

    @Test
    void rejectsMissingTransitionTimeWithoutChangingTheOrder() {
        Order order = newPendingOrder();

        assertThatThrownBy(() -> order.confirm(null)).isInstanceOf(InvalidOrderException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getUpdatedAt()).isEqualTo(CREATED);
    }

    @Test
    void newOrderStartsWithoutRejectionReason() {
        assertThat(newPendingOrder().getRejectionReason()).isNull();
    }

    private static void apply(Order order, Action action) {
        switch (action) {
            case CONFIRM -> order.confirm(CHANGED);
            case REJECT -> order.reject("No stock", CHANGED);
            case CANCEL -> order.cancel(CHANGED);
        }
    }

    private static Order orderIn(OrderStatus status) {
        Order order = newPendingOrder();
        switch (status) {
            case PENDING -> { }
            case CONFIRMED -> order.confirm(PREPARED);
            case REJECTED -> order.reject("No stock", PREPARED);
            case CANCELLED -> order.cancel(PREPARED);
        }
        return order;
    }

    private static Order newPendingOrder() {
        Customer customer = Customer.create(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "Sample Business Standard", "standard@example.test", CustomerType.B2B, "B2B_STANDARD"
        );
        return Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 1, new BigDecimal("84.90"))
        ), new BigDecimal("22.92"), CREATED);
    }
}
