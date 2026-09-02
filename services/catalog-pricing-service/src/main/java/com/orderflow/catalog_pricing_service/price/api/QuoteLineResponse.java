package com.orderflow.catalog_pricing_service.price.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.orderflow.catalog_pricing_service.price.application.QuoteResult;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteLineResponse(
    String sku,
    int quantity,
    BigDecimal unitPriceNet,
    BigDecimal lineTotalNet,
    BigDecimal lineTotalVat,
    BigDecimal lineTotalGross,
    String appliedRule
) {
    public static QuoteLineResponse from(QuoteResult.Line line, boolean b2c) {
        var calculation = line.calculation();
        return new QuoteLineResponse(
            line.sku(), line.quantity(), calculation.unitPriceNet(), calculation.lineTotalNet(),
            b2c ? null : calculation.totalVat(),
            b2c ? calculation.totalGross() : null,
            calculation.appliedRule()
        );
    }
}
