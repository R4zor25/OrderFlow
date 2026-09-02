package com.orderflow.catalog_pricing_service.price.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.orderflow.catalog_pricing_service.price.application.QuoteResult;

public record QuoteResponse(
    String customerGroup,
    String currency,
    List<QuoteLineResponse> lines,
    BigDecimal totalNet,
    BigDecimal totalVat,
    BigDecimal totalGross,
    Instant quotedAt
) {
    public static QuoteResponse from(QuoteResult quote) {
        boolean b2c = "B2C".equals(quote.customerGroup());
        return new QuoteResponse(
            quote.customerGroup(), "EUR",
            quote.lines().stream().map(line -> QuoteLineResponse.from(line, b2c)).toList(),
            quote.totalNet(), quote.totalVat(), quote.totalGross(), quote.quotedAt()
        );
    }
}
