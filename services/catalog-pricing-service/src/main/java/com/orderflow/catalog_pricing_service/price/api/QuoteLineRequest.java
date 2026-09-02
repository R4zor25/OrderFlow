package com.orderflow.catalog_pricing_service.price.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record QuoteLineRequest(
    @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Z0-9][A-Z0-9-]*") String sku,
    @NotNull @Positive Integer quantity
) {
}
