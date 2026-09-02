package com.orderflow.catalog_pricing_service.price.domain;

import java.math.BigDecimal;

public record PriceCalculation(
    BigDecimal unitPriceNet,
    BigDecimal lineTotalNet,
    BigDecimal totalVat,
    BigDecimal totalGross,
    String appliedRule
) {
}
