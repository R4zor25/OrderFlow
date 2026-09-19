package com.orderflow.order.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PlaceOrderRequest(
    @NotEmpty @Size(max = 100) List<@NotNull @Valid PlaceOrderLineRequest> lines
) {
}
