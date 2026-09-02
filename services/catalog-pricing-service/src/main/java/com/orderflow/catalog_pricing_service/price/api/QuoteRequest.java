package com.orderflow.catalog_pricing_service.price.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QuoteRequest(
    @NotBlank @Size(max = 32) String customerGroup,
    @NotEmpty List<@NotNull @Valid QuoteLineRequest> lines
) {
}
