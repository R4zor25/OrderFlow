package com.orderflow.catalog_pricing_service.product.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PriceCalculator {

    private final Clock clock;

    public PriceCalculator(Clock clock) {
        this.clock = clock;
    }

    public PriceCalculation calculate(String group, String sku,
                                      BigDecimal listPriceNet, BigDecimal vatRate,
                                      int quantity, List<PriceTier> tiers) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }

        LocalDate today = LocalDate.now(clock);
        Set<Integer> applicableThresholds = new HashSet<>();
        PriceTier selectedTier = null;

        for (PriceTier tier : tiers) {
            if (!group.equals(tier.group()) || !sku.equals(tier.sku())
                || tier.minQty() > quantity
                || tier.validFrom().isAfter(today)
                || (tier.validTo() != null && tier.validTo().isBefore(today))) {
                continue;
            }

            if (!applicableThresholds.add(tier.minQty())) {
                throw new IllegalStateException("Overlapping price tiers for minimum quantity " + tier.minQty());
            }

            if (selectedTier == null || tier.minQty() > selectedTier.minQty()) {
                selectedTier = tier;
            }
        }

        BigDecimal unitNet = (selectedTier == null ? listPriceNet : selectedTier.unitPriceNet())
            .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal lineNet = unitNet.multiply(BigDecimal.valueOf(quantity))
            .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal vat = lineNet.multiply(vatRate)
            .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal gross = lineNet.add(vat)
            .setScale(2, RoundingMode.HALF_EVEN);
        String rule = selectedTier == null ? "LIST_PRICE" : "TIER_MIN_QTY_" + selectedTier.minQty();

        return new PriceCalculation(unitNet, lineNet, vat, gross, rule);
    }
}
