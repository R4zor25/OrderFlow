package com.orderflow.order.pricing.quote;

import java.math.BigDecimal;

public record QuoteLineResponse(
    String sku,
    int quantity,
    BigDecimal unitPriceNet,
    BigDecimal lineTotalNet,
    BigDecimal lineTotalVat,
    BigDecimal lineTotalGross,
    String appliedRule
) {
}
