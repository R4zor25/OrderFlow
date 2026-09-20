package com.orderflow.catalog_pricing_service.price.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReplacePricesRequest(
    @NotBlank @Size(max = 32) String customerGroup,
    @NotNull @Size(max = 100) List<@NotNull @Valid PriceTierRequest> tiers
) {
}
