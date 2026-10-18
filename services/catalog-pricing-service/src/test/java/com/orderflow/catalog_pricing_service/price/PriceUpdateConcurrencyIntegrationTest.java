package com.orderflow.catalog_pricing_service.price;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.RedisTestConfiguration;
import com.orderflow.catalog_pricing_service.price.application.PriceUpdateService.TierInput;
import com.orderflow.catalog_pricing_service.price.application.PriceUpdateService;
import com.orderflow.catalog_pricing_service.product.domain.Product;
import com.orderflow.catalog_pricing_service.product.infrastructure.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class PriceUpdateConcurrencyIntegrationTest {

    private static final String SKU = "LOCK-TEST-001";

    @Autowired
    private PriceUpdateService prices;

    @Autowired
    private ProductRepository products;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void createProduct() {
        removeProduct();
        products.save(Product.create(SKU, "Lock test product", "TEST", new BigDecimal("100.00"),
            new BigDecimal("0.27"), Instant.now()));
    }

    @AfterEach
    void removeProduct() {
        jdbc.update("delete from catalog.price_list_entry where sku = ?", SKU);
        jdbc.update("delete from catalog.product where sku = ?", SKU);
    }

    @Test
    void waitsForAConcurrentUpdateOfTheSameProduct() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // NO KEY UPDATE leaves the tier insert's foreign-key check free, so only the service's own lock waits here.
            try (var lock = connection.prepareStatement("select id from catalog.product where sku = ? for no key update")) {
                lock.setString(1, SKU);
                lock.executeQuery().close();
            }

            var update = CompletableFuture.runAsync(() -> prices.replace(SKU, "B2B_GOLD", List.of(tier(1, "90.00"))));

            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> !update.isDone());
            connection.rollback();
            update.get(10, TimeUnit.SECONDS);
        }
        assertThat(minQuantities()).containsExactly(1);
    }

    @Test
    void concurrentReplacementsLeaveOneTierList() throws Exception {
        var start = new CountDownLatch(1);
        var first = CompletableFuture.runAsync(() -> {
            awaitStart(start);
            prices.replace(SKU, "B2B_GOLD", List.of(tier(1, "90.00"), tier(10, "80.00")));
        });
        var second = CompletableFuture.runAsync(() -> {
            awaitStart(start);
            prices.replace(SKU, "B2B_GOLD", List.of(tier(5, "85.00"), tier(20, "70.00")));
        });

        start.countDown();
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        assertThat(minQuantities()).isIn(List.of(1, 10), List.of(5, 20));
    }

    private List<Integer> minQuantities() {
        return jdbc.queryForList("""
            select min_qty from catalog.price_list_entry
            where sku = ? and customer_group_code = 'B2B_GOLD' order by min_qty
            """, Integer.class, SKU);
    }

    private static TierInput tier(int minQty, String price) {
        return new TierInput(minQty, new BigDecimal(price), LocalDate.of(2020, 1, 1), null);
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
