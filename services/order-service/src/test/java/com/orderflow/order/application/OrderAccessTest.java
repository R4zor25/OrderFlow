package com.orderflow.order.application;

import java.util.UUID;

import com.orderflow.order.domain.OrderAccessDeniedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderAccessTest {

    private static final UUID OWNER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    void customerPermitsOnlyOwnOrders() {
        var access = OrderAccess.customer(OWNER);
        assertThat(access.permits(OWNER)).isTrue();
        assertThat(access.permits(OTHER)).isFalse();
    }

    @Test
    void adminPermitsEveryOrder() {
        assertThat(OrderAccess.admin().permits(OTHER)).isTrue();
    }

    @Test
    void customerListIsAlwaysScopedToOwnId() {
        assertThat(OrderAccess.customer(OWNER).listFilter(null)).isEqualTo(OWNER);
        assertThat(OrderAccess.customer(OWNER).listFilter(OWNER)).isEqualTo(OWNER);
    }

    @Test
    void customerCannotFilterForAnotherCustomer() {
        assertThatThrownBy(() -> OrderAccess.customer(OWNER).listFilter(OTHER))
            .isInstanceOf(OrderAccessDeniedException.class);
    }

    @Test
    void adminFilterIsOptional() {
        assertThat(OrderAccess.admin().listFilter(null)).isNull();
        assertThat(OrderAccess.admin().listFilter(OTHER)).isEqualTo(OTHER);
    }

    @Test
    void customerAccessRequiresCustomerId() {
        assertThatThrownBy(() -> OrderAccess.customer(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsCustomerAccessWithoutCustomerId() {
        assertThatThrownBy(() -> new OrderAccess(false, null)).isInstanceOf(NullPointerException.class);
        assertThat(OrderAccess.admin().customerId()).isNull();
    }
}
