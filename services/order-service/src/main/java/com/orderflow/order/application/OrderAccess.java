package com.orderflow.order.application;

import java.util.Objects;
import java.util.UUID;

import com.orderflow.order.domain.OrderAccessDeniedException;

public record OrderAccess(boolean administrator, UUID customerId) {

    public OrderAccess {
        if (!administrator) {
            Objects.requireNonNull(customerId, "customerId");
        }
    }

    public static OrderAccess admin() {
        return new OrderAccess(true, null);
    }

    public static OrderAccess customer(UUID customerId) {
        return new OrderAccess(false, customerId);
    }

    public boolean permits(UUID ownerId) {
        return administrator || customerId.equals(ownerId);
    }

    public UUID listFilter(UUID requested) {
        if (administrator) {
            return requested;
        }
        if (requested != null && !requested.equals(customerId)) {
            throw new OrderAccessDeniedException("Customers can only list their own orders");
        }
        return customerId;
    }
}
