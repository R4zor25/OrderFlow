package com.orderflow.catalog_pricing_service.price;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.RedisTestConfiguration;
import com.orderflow.catalog_pricing_service.price.domain.PriceTier;
import com.orderflow.catalog_pricing_service.price.infrastructure.CachedPriceTiers;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceTierCache;
import com.orderflow.catalog_pricing_service.price.infrastructure.VersionedPriceTierWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class VersionedPriceTierWriterIntegrationTest {

    @Autowired
    private VersionedPriceTierWriter writer;

    @Autowired
    private CacheManager caches;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void writesAnEmptyKeyThatTheCacheCanReadBackWithItsTimeToLive() {
        assertThat(writer.write("B2B_GOLD", "WRITER-001", 1, List.of(tier("WRITER-001", "80.00")))).isTrue();

        assertThat(cached("WRITER-001").version()).isEqualTo(1);
        assertThat(cached("WRITER-001").tiers()).extracting(PriceTier::unitPriceNet)
            .containsExactly(new BigDecimal("80.00"));
        assertThat(redis.getExpire("price-tiers::B2B_GOLD:WRITER-001", TimeUnit.SECONDS))
            .isBetween(1L, PriceTierCache.TIME_TO_LIVE.toSeconds());
    }

    @Test
    void replacesOnlyWithAHigherVersion() {
        writer.write("B2B_GOLD", "WRITER-002", 5, List.of(tier("WRITER-002", "80.00")));

        assertThat(writer.write("B2B_GOLD", "WRITER-002", 4, List.of(tier("WRITER-002", "90.00")))).isFalse();
        assertThat(writer.write("B2B_GOLD", "WRITER-002", 5, List.of(tier("WRITER-002", "91.00")))).isFalse();
        assertThat(cached("WRITER-002").tiers()).extracting(PriceTier::unitPriceNet)
            .containsExactly(new BigDecimal("80.00"));

        assertThat(writer.write("B2B_GOLD", "WRITER-002", 6, List.of(tier("WRITER-002", "70.00")))).isTrue();
        assertThat(cached("WRITER-002").tiers()).extracting(PriceTier::unitPriceNet)
            .containsExactly(new BigDecimal("70.00"));
    }

    @Test
    void readsAnEntryWrittenBeforeVersionsExistedAsVersionZero() {
        redis.opsForValue().set("price-tiers::B2B_GOLD:WRITER-003", """
            {"tiers":[{"group":"B2B_GOLD","sku":"WRITER-003","minQty":1,"unitPriceNet":80.00,
            "validFrom":"2020-01-01","validTo":null}]}""");

        assertThat(cached("WRITER-003").version()).isZero();
        assertThat(writer.write("B2B_GOLD", "WRITER-003", 1, List.of(tier("WRITER-003", "70.00")))).isTrue();
    }

    private CachedPriceTiers cached(String sku) {
        return caches.getCache(PriceTierCache.CACHE_NAME).get("B2B_GOLD:" + sku, CachedPriceTiers.class);
    }

    private static PriceTier tier(String sku, String price) {
        return new PriceTier("B2B_GOLD", sku, 1, new BigDecimal(price), LocalDate.of(2020, 1, 1), null);
    }
}
