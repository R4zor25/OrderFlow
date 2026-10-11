package com.orderflow.catalog_pricing_service.price;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.orderflow.catalog_pricing_service.price.domain.PriceListEntry;
import com.orderflow.catalog_pricing_service.price.domain.PriceTier;
import com.orderflow.catalog_pricing_service.price.infrastructure.CachedPriceTiers;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceListEntryRepository;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceListEntryRepository.PriceVersion;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceTierCache;
import com.orderflow.catalog_pricing_service.price.infrastructure.VersionedPriceTierWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceTierCacheTest {

    private final PriceListEntryRepository repository = mock(PriceListEntryRepository.class);
    private final VersionedPriceTierWriter writer = mock(VersionedPriceTierWriter.class);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void writesTheNewListWithItsVersionImmediatelyOutsideATransaction() {
        var cache = new PriceTierCache(new ConcurrentMapCacheManager("price-tiers"), repository, writer);

        cache.replaced("B2B_GOLD", "CHAIR-001", 3, List.of(tier("70.00")));

        verify(writer).write("B2B_GOLD", "CHAIR-001", 3, List.of(tier("70.00")));
    }

    @Test
    void loaderWritesTheVersionItReadBeforeTheTiers() {
        when(repository.findPriceVersions(anyCollection())).thenReturn(List.of(version("CHAIR-001", 5)));
        when(repository.findAllByCustomerGroupCodeAndSkuIn(eq("B2B_GOLD"), anyCollection()))
            .thenReturn(List.of(entry("84.90")));

        new PriceTierCache(new ConcurrentMapCacheManager("price-tiers"), repository, writer)
            .find("B2B_GOLD", Set.of("CHAIR-001"));

        InOrder order = inOrder(repository, writer);
        order.verify(repository).findPriceVersions(anyCollection());
        order.verify(repository).findAllByCustomerGroupCodeAndSkuIn(eq("B2B_GOLD"), anyCollection());
        order.verify(writer).write(eq("B2B_GOLD"), eq("CHAIR-001"), eq(5L), anyList());
    }

    @Test
    void readsFromTheDatabaseWhenTheCacheFails() {
        var failing = mock(Cache.class);
        when(failing.get(any(), eq(CachedPriceTiers.class)))
            .thenThrow(new RedisConnectionFailureException("down"));
        var manager = mock(CacheManager.class);
        when(manager.getCache("price-tiers")).thenReturn(failing);
        when(repository.findAllByCustomerGroupCodeAndSkuIn(eq("B2B_GOLD"), anyCollection()))
            .thenReturn(List.of(entry("84.90"), entry("CHAIR-002", "50.00")));

        var loaded = new PriceTierCache(manager, repository, writer).find("B2B_GOLD", Set.of("CHAIR-001", "CHAIR-002"));

        assertThat(loaded).containsOnlyKeys("CHAIR-001", "CHAIR-002");
        verify(failing, times(1)).get(any(), eq(CachedPriceTiers.class));
        verify(writer, never()).write(anyString(), anyString(), anyLong(), anyList());
    }

    @Test
    void stopsWritingAfterTheFirstWriteFailure() {
        when(repository.findAllByCustomerGroupCodeAndSkuIn(eq("B2B_GOLD"), anyCollection()))
            .thenReturn(List.of(entry("84.90"), entry("CHAIR-002", "50.00")));
        when(writer.write(anyString(), anyString(), anyLong(), anyList()))
            .thenThrow(new RedisConnectionFailureException("down"));

        var loaded = new PriceTierCache(new ConcurrentMapCacheManager("price-tiers"), repository, writer)
            .find("B2B_GOLD", Set.of("CHAIR-001", "CHAIR-002"));

        assertThat(loaded).containsOnlyKeys("CHAIR-001", "CHAIR-002");
        verify(writer, times(1)).write(anyString(), anyString(), anyLong(), anyList());
    }

    @Test
    void doesNotRefreshTheCacheWhenThePriceChangeRollsBack() {
        var cache = new PriceTierCache(new ConcurrentMapCacheManager("price-tiers"), repository, writer);
        TransactionSynchronizationManager.initSynchronization();

        cache.replaced("B2B_GOLD", "CHAIR-001", 3, List.of(tier("70.00")));
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(writer, never()).write(anyString(), anyString(), anyLong(), anyList());
    }

    private static PriceVersion version(String sku, long value) {
        return new PriceVersion() {
            @Override
            public String getSku() {
                return sku;
            }

            @Override
            public long getPriceVersion() {
                return value;
            }
        };
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
