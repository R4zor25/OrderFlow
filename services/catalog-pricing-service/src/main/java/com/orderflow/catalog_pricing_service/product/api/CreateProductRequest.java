package com.orderflow.catalog_pricing_service.product.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
    @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Z0-9][A-Z0-9-]*") String sku,
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 64) String category,
    @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal listPriceNet,
    @NotNull @Digits(integer = 1, fraction = 2) BigDecimal vatRate
) {
}
