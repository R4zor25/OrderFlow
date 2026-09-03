package com.orderflow.catalog_pricing_service.price.infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import com.orderflow.catalog_pricing_service.price.domain.PriceListEntry;
import com.orderflow.catalog_pricing_service.price.domain.PriceTier;

@Component
public class PriceTierCache {

    private final Cache cache;
    private final PriceListEntryRepository repository;

    public PriceTierCache(CacheManager cacheManager, PriceListEntryRepository repository) {
        this.cache = cacheManager.getCache("price-tiers");
        if (this.cache == null) {
            throw new IllegalStateException("price-tiers cache is not configured");
        }
        this.repository = repository;
    }

    public Map<String, List<PriceTier>> find(String group, Set<String> skus) {
        Map<String, List<PriceTier>> result = new HashMap<>();
        Set<String> misses = new TreeSet<>();
        for (String sku : skus) {
            CachedPriceTiers cached = cache.get(key(group, sku), CachedPriceTiers.class);
            if (cached == null) {
                misses.add(sku);
            } else {
                result.put(sku, cached.tiers());
            }
        }
        if (!misses.isEmpty()) {
            Map<String, List<PriceTier>> loaded = new HashMap<>();
            for (PriceListEntry entry : repository.findAllByCustomerGroupCodeAndSkuIn(group, misses)) {
                loaded.computeIfAbsent(entry.getSku(), ignored -> new ArrayList<>())
                    .add(new PriceTier(entry.getCustomerGroupCode(), entry.getSku(), entry.getMinQty(),
                        entry.getUnitPriceNet(), entry.getValidFrom(), entry.getValidTo()));
            }
            for (String sku : misses) {
                List<PriceTier> tiers = List.copyOf(loaded.getOrDefault(sku, List.of()));
                cache.put(key(group, sku), new CachedPriceTiers(tiers));
                result.put(sku, tiers);
            }
        }
        return result;
    }

    public void evict(String group, String sku) {
        cache.evict(key(group, sku));
    }

    private String key(String group, String sku) {
        return group + ":" + sku;
    }
}
