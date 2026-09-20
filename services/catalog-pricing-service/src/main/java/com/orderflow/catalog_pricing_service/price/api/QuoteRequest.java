package com.orderflow.catalog_pricing_service.price.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QuoteRequest(
    @NotBlank @Size(max = 32) String customerGroup,
    @NotEmpty @Size(max = 100) List<@NotNull @Valid QuoteLineRequest> lines
) {

    @AssertTrue(message = "SKUs must be unique")
    public boolean isDistinctSkus() {
        if (lines == null) {
            return true;
        }
        List<String> skus = lines.stream().filter(Objects::nonNull).map(QuoteLineRequest::sku)
            .filter(Objects::nonNull).toList();
        return new HashSet<>(skus).size() == skus.size();
    }
}
