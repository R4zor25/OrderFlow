package com.orderflow.catalog_pricing_service.price.infrastructure;

import java.util.List;

import com.orderflow.catalog_pricing_service.price.domain.PriceTier;

public record CachedPriceTiers(List<PriceTier> tiers) {
}
