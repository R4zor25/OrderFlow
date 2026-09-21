package com.orderflow.catalog_pricing_service.price;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.orderflow.catalog_pricing_service.price.domain.PriceListEntry;
import com.orderflow.catalog_pricing_service.price.domain.PriceTier;
import com.orderflow.catalog_pricing_service.price.infrastructure.CachedPriceTiers;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceListEntryRepository;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceTierCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceTierCacheTest {

    private final PriceListEntryRepository repository = mock(PriceListEntryRepository.class);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void writesTheNewListImmediatelyOutsideATransaction() {
        var manager = new ConcurrentMapCacheManager("price-tiers");

        new PriceTierCache(manager, repository).replaced("B2B_GOLD", "CHAIR-001", List.of(tier("70.00")));

        assertThat(manager.getCache("price-tiers").get("B2B_GOLD:CHAIR-001", CachedPriceTiers.class).tiers())
            .extracting(PriceTier::unitPriceNet).containsExactly(new BigDecimal("70.00"));
    }

    @Test
    void priceChangeDuringALoadIsNotOverwrittenByTheLoadedOldList() {
        var manager = new ConcurrentMapCacheManager("price-tiers");
        var cache = new PriceTierCache(manager, repository);
        // The price change commits while this quote is reading the old tiers from the database.
        when(repository.findAllByCustomerGroupCodeAndSkuIn(eq("B2B_GOLD"), anyCollection())).thenAnswer(invocation -> {
            cache.replaced("B2B_GOLD", "CHAIR-001", List.of(tier("70.00")));
            return List.of(entry("84.90"));
        });

        cache.find("B2B_GOLD", Set.of("CHAIR-001"));

        assertThat(manager.getCache("price-tiers").get("B2B_GOLD:CHAIR-001", CachedPriceTiers.class).tiers())
            .extracting(PriceTier::unitPriceNet).containsExactly(new BigDecimal("70.00"));
    }

    @Test
    void readsFromTheDatabaseWhenTheCacheFails() {
        var failing = mock(Cache.class);
        when(failing.get(any(), eq(CachedPriceTiers.class))).thenThrow(new RedisConnectionFailureException("down"));
        when(failing.putIfAbsent(any(), any())).thenThrow(new RedisConnectionFailureException("down"));
        var manager = mock(CacheManager.class);
        when(manager.getCache("price-tiers")).thenReturn(failing);
        when(repository.findAllByCustomerGroupCodeAndSkuIn(eq("B2B_GOLD"), anyCollection()))
            .thenReturn(List.of(entry("84.90"), entry("CHAIR-002", "50.00")));

        var loaded = new PriceTierCache(manager, repository).find("B2B_GOLD", Set.of("CHAIR-001", "CHAIR-002"));

        assertThat(loaded).containsOnlyKeys("CHAIR-001", "CHAIR-002");
        verify(failing, times(1)).get(any(), eq(CachedPriceTiers.class));
    }

    @Test
    void doesNotRefreshTheCacheWhenThePriceChangeRollsBack() {
        var manager = new ConcurrentMapCacheManager("price-tiers");
        var cache = new PriceTierCache(manager, repository);
        TransactionSynchronizationManager.initSynchronization();

        cache.replaced("B2B_GOLD", "CHAIR-001", List.of(tier("70.00")));
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(manager.getCache("price-tiers").get("B2B_GOLD:CHAIR-001")).isNull();
    }

    private static PriceTier tier(String price) {
        return new PriceTier("B2B_GOLD", "CHAIR-001", 1, new BigDecimal(price), LocalDate.of(2020, 1, 1), null);
    }

    private static PriceListEntry entry(String price) {
        return entry("CHAIR-001", price);
    }

    private static PriceListEntry entry(String sku, String price) {
        return PriceListEntry.create("B2B_GOLD", sku, 1, new BigDecimal(price), LocalDate.of(2020, 1, 1), null);
    }
}
