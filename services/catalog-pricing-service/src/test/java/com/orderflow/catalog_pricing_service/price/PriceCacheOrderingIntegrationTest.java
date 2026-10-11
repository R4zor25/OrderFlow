package com.orderflow.catalog_pricing_service.price;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.RedisTestConfiguration;
import com.orderflow.catalog_pricing_service.price.application.PriceUpdateService;
import com.orderflow.catalog_pricing_service.price.application.PriceUpdateService.TierInput;
import com.orderflow.catalog_pricing_service.price.domain.PriceTier;
import com.orderflow.catalog_pricing_service.price.infrastructure.CachedPriceTiers;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceTierCache;
import com.orderflow.catalog_pricing_service.price.infrastructure.VersionedPriceTierWriter;
import com.orderflow.catalog_pricing_service.product.domain.Product;
import com.orderflow.catalog_pricing_service.product.infrastructure.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class PriceCacheOrderingIntegrationTest {

    @Autowired
    private PriceUpdateService prices;

    @Autowired
    private ProductRepository products;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private CacheManager caches;

    @Autowired
    private VersionedPriceTierWriter writer;

    @Test
    void keepsTheNewestPriceWhenAnOlderCommitWritesTheCacheLate() throws Exception {
        String sku = "ORDERING-001";
        createProduct(sku);
        var committed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // The first replacement commits, then stalls before its cache write, as a descheduled thread would.
        var first = CompletableFuture.runAsync(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public int getOrder() {
                    return Integer.MIN_VALUE;
                }

                @Override
                public void afterCommit() {
                    committed.countDown();
                    try {
                        release.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            prices.replace(sku, "B2B_GOLD", List.of(tier("90.00")));
        }));
        try {
            assertThat(committed.await(15, TimeUnit.SECONDS)).isTrue();
            prices.replace(sku, "B2B_GOLD", List.of(tier("70.00")));
        } finally {
            release.countDown();
        }
        first.get(15, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("select unit_price_net from catalog.price_list_entry where sku = ?",
            BigDecimal.class, sku)).isEqualByComparingTo("70.00");
        assertThat(jdbc.queryForObject("select price_version from catalog.product where sku = ?", Long.class, sku))
            .isEqualTo(2L);
        assertThat(cachedPrices(sku)).containsExactly(new BigDecimal("70.00"));
    }

    @Test
    void anOlderVersionDoesNotReplaceANewerCachedPrice() {
        String sku = "ORDERING-002";
        createProduct(sku);
        prices.replace(sku, "B2B_GOLD", List.of(tier("70.00")));

        // A quote that loaded the tiers before the price change writes them with the version it read then.
        writer.write("B2B_GOLD", sku, 0, List.of(new PriceTier("B2B_GOLD", sku, 1, new BigDecimal("90.00"),
            LocalDate.of(2020, 1, 1), null)));

        assertThat(cachedPrices(sku)).containsExactly(new BigDecimal("70.00"));
    }

    private void createProduct(String sku) {
        products.saveAndFlush(Product.create(sku, "Ordering test", "TEST", new BigDecimal("100.00"),
            new BigDecimal("0.27"), Instant.now()));
    }

    private List<BigDecimal> cachedPrices(String sku) {
        var cached = caches.getCache(PriceTierCache.CACHE_NAME).get("B2B_GOLD:" + sku, CachedPriceTiers.class);
        assertThat(cached).as("cached tiers of " + sku).isNotNull();
        return cached.tiers().stream().map(PriceTier::unitPriceNet).toList();
    }

    private static TierInput tier(String price) {
        return new TierInput(1, new BigDecimal(price), LocalDate.of(2020, 1, 1), null);
    }
}
