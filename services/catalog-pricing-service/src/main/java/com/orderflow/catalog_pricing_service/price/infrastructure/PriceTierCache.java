package com.orderflow.catalog_pricing_service.price.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.orderflow.catalog_pricing_service.price.domain.PriceListEntry;
import com.orderflow.catalog_pricing_service.price.domain.PriceTier;

@Component
public class PriceTierCache {

    public static final String CACHE_NAME = "price-tiers";
    public static final Duration TIME_TO_LIVE = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(PriceTierCache.class);

    private final Cache cache;
    private final PriceListEntryRepository repository;
    private final VersionedPriceTierWriter writer;

    public PriceTierCache(CacheManager cacheManager, PriceListEntryRepository repository,
                          VersionedPriceTierWriter writer) {
        this.cache = cacheManager.getCache(CACHE_NAME);
        if (this.cache == null) {
            throw new IllegalStateException(CACHE_NAME + " cache is not configured");
        }
        this.repository = repository;
        this.writer = writer;
    }

    public Map<String, List<PriceTier>> find(String group, Set<String> skus) {
        Map<String, List<PriceTier>> result = new HashMap<>();
        Set<String> misses = new TreeSet<>();
        boolean cacheAvailable = true;
        for (String sku : skus) {
            CachedPriceTiers cached = null;
            if (cacheAvailable) {
                try {
                    cached = cache.get(key(group, sku), CachedPriceTiers.class);
                } catch (RuntimeException failure) {
                    cacheAvailable = false;
                    log.warn("Price cache unavailable, reading from the database cause={}",
                        failure.getClass().getSimpleName());
                }
            }
            if (cached == null) {
                misses.add(sku);
            } else {
                result.put(sku, cached.tiers());
            }
        }
        if (!misses.isEmpty()) {
            // Versions are read before the tiers, so a list is never labelled newer than the data it holds.
            Map<String, Long> versions = new HashMap<>();
            for (var version : repository.findPriceVersions(misses)) {
                versions.put(version.getSku(), version.getPriceVersion());
            }
            Map<String, List<PriceTier>> loaded = new HashMap<>();
            for (PriceListEntry entry : repository.findAllByCustomerGroupCodeAndSkuIn(group, misses)) {
                loaded.computeIfAbsent(entry.getSku(), ignored -> new ArrayList<>()).add(entry.toTier());
            }
            for (String sku : misses) {
                List<PriceTier> tiers = List.copyOf(loaded.getOrDefault(sku, List.of()));
                result.put(sku, tiers);
                if (cacheAvailable) {
                    cacheAvailable = cacheLoaded(group, sku, versions.getOrDefault(sku, 0L), tiers);
                }
            }
        }
        return result;
    }

    public void replaced(String group, String sku, long version, List<PriceTier> tiers) {
        List<PriceTier> fresh = List.copyOf(tiers);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh(group, sku, version, fresh);
                }
            });
        } else {
            refresh(group, sku, version, fresh);
        }
    }

    private boolean cacheLoaded(String group, String sku, long version, List<PriceTier> tiers) {
        try {
            writer.write(group, sku, version, tiers);
            return true;
        } catch (RuntimeException failure) {
            log.warn("Price cache unavailable, reading from the database cause={}", failure.getClass().getSimpleName());
            return false;
        }
    }

    private void refresh(String group, String sku, long version, List<PriceTier> tiers) {
        try {
            writer.write(group, sku, version, tiers);
        } catch (RuntimeException failure) {
            log.warn("Price cache not refreshed after a price change group={} sku={} cause={}",
                group, sku, failure.getClass().getSimpleName());
        }
    }

    static String key(String group, String sku) {
        return group + ":" + sku;
    }
}
