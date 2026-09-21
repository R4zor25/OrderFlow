package com.orderflow.catalog_pricing_service.price.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.orderflow.catalog_pricing_service.price.domain.PriceListEntry;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceListEntryRepository;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceTierCache;
import com.orderflow.catalog_pricing_service.product.infrastructure.ProductRepository;

@Service
public class PriceUpdateService {

    private final ProductRepository products;
    private final PriceListEntryRepository prices;
    private final PriceTierCache cache;

    public PriceUpdateService(ProductRepository products, PriceListEntryRepository prices, PriceTierCache cache) {
        this.products = products;
        this.prices = prices;
        this.cache = cache;
    }

    @Transactional
    public void replace(String sku, String group, List<TierInput> tiers) {
        // The row lock serializes replacements of one product, so a second request deletes the first one's tiers.
        if (products.findLockedBySku(sku).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        if (!prices.customerGroupExists(group)) {
            throw new UnknownCustomerGroupException(group);
        }
        validate(tiers);
        prices.deleteForGroupAndSku(group, sku);
        List<PriceListEntry> saved = prices.saveAll(tiers.stream()
            .map(tier -> PriceListEntry.create(group, sku, tier.minQty(), tier.unitPriceNet(),
                tier.validFrom(), tier.validTo()))
            .toList());
        cache.replaced(group, sku, saved.stream().map(PriceListEntry::toTier).toList());
    }

    private void validate(List<TierInput> tiers) {
        for (int i = 0; i < tiers.size(); i++) {
            TierInput left = tiers.get(i);
            if (left.validTo() != null && left.validTo().isBefore(left.validFrom())) {
                throw new InvalidPriceListException("validTo must be on or after validFrom");
            }
            for (int j = i + 1; j < tiers.size(); j++) {
                TierInput right = tiers.get(j);
                if (left.minQty() == right.minQty() && overlaps(left, right)) {
                    throw new InvalidPriceListException("Tiers with the same minQty must not overlap");
                }
            }
        }
    }

    private boolean overlaps(TierInput left, TierInput right) {
        return (left.validTo() == null || !left.validTo().isBefore(right.validFrom()))
            && (right.validTo() == null || !right.validTo().isBefore(left.validFrom()));
    }

    public record TierInput(int minQty, BigDecimal unitPriceNet, LocalDate validFrom, LocalDate validTo) {
    }
}
