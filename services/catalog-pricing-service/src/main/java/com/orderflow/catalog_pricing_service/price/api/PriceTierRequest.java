package com.orderflow.catalog_pricing_service.price.api;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record PriceTierRequest(
    @Positive int minQty,
    @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal unitPriceNet,
    @NotNull LocalDate validFrom,
    LocalDate validTo
) {
}
