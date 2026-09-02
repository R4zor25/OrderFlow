package com.orderflow.catalog_pricing_service.price.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceTier(
    String group,
    String sku,
    int minQty,
    BigDecimal unitPriceNet,
    LocalDate validFrom,
    LocalDate validTo
) {

}
