package com.orderflow.catalog_pricing_service.price;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.orderflow.catalog_pricing_service.price.domain.PriceCalculator;
import com.orderflow.catalog_pricing_service.price.domain.PriceTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceCalculatorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-09-24T10:15:30Z"), ZoneOffset.UTC
    );

    private final PriceCalculator calculator = new PriceCalculator(FIXED_CLOCK);

    @ParameterizedTest
    @CsvSource({
        "1, 89.90, TIER_MIN_QTY_1",
        "19, 89.90, TIER_MIN_QTY_1",
        "20, 79.90, TIER_MIN_QTY_20",
        "21, 79.90, TIER_MIN_QTY_20"
    })
    void selectsHighestApplicableTier(int quantity, String expectedUnitNet, String expectedRule) {
        var result = calculator.calculate(
            "B2B_STANDARD", "CHAIR-001", money("99.90"), money("0.27"), quantity,
            List.of(
                tier("B2B_STANDARD", "CHAIR-001", 1, "89.90", "2020-01-01", null),
                tier("B2B_STANDARD", "CHAIR-001", 20, "79.90", "2020-01-01", null)
            )
        );

        assertThat(result.unitPriceNet()).isEqualByComparingTo(expectedUnitNet);
        assertThat(result.appliedRule()).isEqualTo(expectedRule);
        assertThat(result.lineTotalNet()).isEqualByComparingTo(
            money(expectedUnitNet).multiply(BigDecimal.valueOf(quantity))
        );
        assertThat(result.unitPriceNet().scale()).isEqualTo(2);
        assertThat(result.lineTotalNet().scale()).isEqualTo(2);
    }

    @Test
    void usesListPriceWhenNoTierMatches() {
        var result = calculator.calculate(
            "B2C", "CHAIR-001", money("99.90"), money("0.27"), 2, List.of()
        );

        assertThat(result.unitPriceNet()).isEqualByComparingTo("99.90");
        assertThat(result.lineTotalNet()).isEqualByComparingTo("199.80");
        assertThat(result.totalVat()).isEqualByComparingTo("53.95");
        assertThat(result.totalGross()).isEqualByComparingTo("253.75");
        assertThat(result.appliedRule()).isEqualTo("LIST_PRICE");
    }

    @Test
    void ignoresTiersForOtherCustomerGroupsAndSkus() {
        var result = calculator.calculate(
            "B2B_STANDARD", "CHAIR-001", money("99.90"), money("0.27"), 20,
            List.of(
                tier("B2B_GOLD", "CHAIR-001", 1, "10.00", "2020-01-01", null),
                tier("B2B_STANDARD", "DESK-001", 1, "20.00", "2020-01-01", null)
            )
        );

        assertThat(result.unitPriceNet()).isEqualByComparingTo("99.90");
        assertThat(result.appliedRule()).isEqualTo("LIST_PRICE");
    }

    @Test
    void includesTodayAtBothDateBoundaries() {
        var result = calculator.calculate(
            "B2B_STANDARD", "CHAIR-001", money("99.90"), money("0.27"), 1,
            List.of(
                tier("B2B_STANDARD", "CHAIR-001", 1, "70.00", "2020-01-01", "2026-09-23"),
                tier("B2B_STANDARD", "CHAIR-001", 1, "75.00", "2026-09-25", null),
                tier("B2B_STANDARD", "CHAIR-001", 1, "80.00", "2026-09-24", "2026-09-24")
            )
        );

        assertThat(result.unitPriceNet()).isEqualByComparingTo("80.00");
    }

    @Test
    void rejectsOverlappingTiersAtTheSameQuantityThreshold() {
        var tiers = List.of(
            tier("B2B_STANDARD", "CHAIR-001", 1, "89.90", "2020-01-01", null),
            tier("B2B_STANDARD", "CHAIR-001", 1, "84.90", "2026-01-01", null)
        );

        assertThatThrownBy(() -> calculator.calculate(
            "B2B_STANDARD", "CHAIR-001", money("99.90"), money("0.27"), 1, tiers
        )).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({"0.10, 0.02, 0.12", "0.30, 0.08, 0.38"})
    void roundsVatHalfEven(String unitNet, String expectedVat, String expectedGross) {
        var result = calculator.calculate(
            "B2C", "SMALL-001", money(unitNet), money("0.25"), 1, List.of()
        );

        assertThat(result.totalVat()).isEqualByComparingTo(expectedVat);
        assertThat(result.totalGross()).isEqualByComparingTo(expectedGross);
        assertThat(result.totalVat().scale()).isEqualTo(2);
        assertThat(result.totalGross().scale()).isEqualTo(2);
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> calculator.calculate(
            "B2C", "CHAIR-001", money("99.90"), money("0.27"), 0, List.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static PriceTier tier(String group, String sku, int minQty, String price,
                                  String validFrom, String validTo) {
        return new PriceTier(
            group, sku, minQty, money(price), LocalDate.parse(validFrom),
            validTo == null ? null : LocalDate.parse(validTo)
        );
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
