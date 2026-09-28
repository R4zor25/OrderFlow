package com.orderflow.catalog_pricing_service.price;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.RedisTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMetrics
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class PriceCacheMetricsIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void countsPriceCacheMissThenHitAndPublishesThemToPrometheus() throws Exception {
        cacheManager.getCache("price-tiers").clear();
        double misses = gets("miss");
        double hits = gets("hit");

        quote();
        assertThat(gets("miss")).isEqualTo(misses + 1);
        quote();
        assertThat(gets("hit")).isEqualTo(hits + 1);

        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("cache_gets_total{cache=\"price-tiers\"")));
    }

    private double gets(String result) {
        var counter = registry.find("cache.gets").tags("cache", "price-tiers", "result", result).functionCounter();
        return counter == null ? 0.0 : counter.count();
    }

    private void quote() throws Exception {
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerGroup\":\"B2B_GOLD\",\"lines\":[{\"sku\":\"CHAIR-001\",\"quantity\":1}]}"))
            .andExpect(status().isOk());
    }
}
