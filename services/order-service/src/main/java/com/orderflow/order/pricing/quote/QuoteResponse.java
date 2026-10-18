package com.orderflow.order.pricing.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record QuoteResponse(
    String customerGroup,
    String currency,
    List<QuoteLineResponse> lines,
    BigDecimal totalNet,
    BigDecimal totalVat,
    BigDecimal totalGross,
    Instant quotedAt
) {
}
