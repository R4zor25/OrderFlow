package com.orderflow.catalog_pricing_service.price.infrastructure;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import com.orderflow.catalog_pricing_service.price.domain.PriceTier;

// Entries written before versions existed have no version; reading them as 0 lets any versioned write replace them.
public record CachedPriceTiers(@JsonSetter(nulls = Nulls.AS_EMPTY) long version, List<PriceTier> tiers) {
}
