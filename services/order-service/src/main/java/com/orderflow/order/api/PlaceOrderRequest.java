package com.orderflow.order.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record PlaceOrderRequest(
    @NotNull UUID customerId,
    @NotEmpty List<@NotNull @Valid PlaceOrderLineRequest> lines
) {
}
