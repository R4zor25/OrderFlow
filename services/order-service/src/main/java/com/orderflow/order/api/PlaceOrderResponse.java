package com.orderflow.order.api;

import java.math.BigDecimal;
import java.util.UUID;

import com.orderflow.order.application.PlaceOrderResult;
import com.orderflow.order.domain.OrderStatus;

public record PlaceOrderResponse(
    UUID id,
    OrderStatus status,
    String currency,
    BigDecimal totalNet,
    BigDecimal totalVat,
    BigDecimal totalGross
) {
    public static PlaceOrderResponse from(PlaceOrderResult result) {
        return new PlaceOrderResponse(result.id(), result.status(), result.currency(),
            result.totalNet(), result.totalVat(), result.totalGross());
    }
}
