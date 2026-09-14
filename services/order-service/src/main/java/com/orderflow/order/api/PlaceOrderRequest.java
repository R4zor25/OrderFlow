package com.orderflow.order.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record PlaceOrderRequest(
    @NotEmpty List<@NotNull @Valid PlaceOrderLineRequest> lines
) {
}
