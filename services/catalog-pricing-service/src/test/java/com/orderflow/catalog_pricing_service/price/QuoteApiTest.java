package com.orderflow.catalog_pricing_service.price;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.RedisTestConfiguration;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceListEntryRepository;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceTierCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class QuoteApiTest {

    private final MockMvc mvc;
    private final CacheManager cacheManager;

    @MockitoSpyBean
    private PriceListEntryRepository priceListEntryRepository;

    @Autowired
    QuoteApiTest(MockMvc mvc, CacheManager cacheManager) {
        this.mvc = mvc;
        this.cacheManager = cacheManager;
    }

    @BeforeEach
    void clearPriceCache() {
        cacheManager.getCache("price-tiers").clear();
    }

    @Test
    void coldCacheLoadsMultipleSkusInOneRepositoryCall() throws Exception {
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2B_GOLD","lines":[
                      {"sku":"CHAIR-001","quantity":1},
                      {"sku":"LAMP-002","quantity":1}
                    ]}
                    """))
            .andExpect(status().isOk());
        verify(priceListEntryRepository, times(1))
            .findAllByCustomerGroupCodeAndSkuIn(eq("B2B_GOLD"), eq(java.util.Set.of("CHAIR-001", "LAMP-002")));
    }

    @Test
    void repeatedQuoteReadsPriceTiersFromRepositoryOnlyOnce() throws Exception {
        String request = """
            {"customerGroup":"B2B_GOLD","lines":[{"sku":"CHAIR-001","quantity":25}]}
            """;
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote"))).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote"))).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk());
        verify(priceListEntryRepository, times(1))
            .findAllByCustomerGroupCodeAndSkuIn(eq("B2B_GOLD"), anyCollection());
    }

    @Test
    void quoteSeesEachNewPriceRightAfterAPriceUpdate() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sku":"CACHE-STEP10","name":"Cache test product","category":"TEST",
                     "listPriceNet":100.00,"vatRate":0.27}
                    """))
            .andExpect(status().isCreated());
        String quote = """
            {"customerGroup":"B2B_GOLD","lines":[{"sku":"CACHE-STEP10","quantity":1}]}
            """;
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote"))).contentType(MediaType.APPLICATION_JSON).content(quote))
            .andExpect(jsonPath("$.lines[0].unitPriceNet").value(100.00));
        mvc.perform(put("/api/v1/products/CACHE-STEP10/prices").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2B_GOLD","tiers":[
                      {"minQty":1,"unitPriceNet":80.00,"validFrom":"2020-01-01"}
                    ]}
                    """))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote"))).contentType(MediaType.APPLICATION_JSON).content(quote))
            .andExpect(jsonPath("$.lines[0].unitPriceNet").value(80.00));
        mvc.perform(put("/api/v1/products/CACHE-STEP10/prices").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2B_GOLD","tiers":[
                      {"minQty":1,"unitPriceNet":70.00,"validFrom":"2020-01-01"}
                    ]}
                    """))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote"))).contentType(MediaType.APPLICATION_JSON).content(quote))
            .andExpect(jsonPath("$.lines[0].unitPriceNet").value(70.00));
    }

    @Test
    void rejectsOverlappingPriceTiersWithoutChangingExistingPrices() throws Exception {
        mvc.perform(put("/api/v1/products/CHAIR-001/prices").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2B_GOLD","tiers":[
                      {"minQty":1,"unitPriceNet":80.00,"validFrom":"2020-01-01","validTo":"2030-01-01"},
                      {"minQty":1,"unitPriceNet":70.00,"validFrom":"2025-01-01"}
                    ]}
                    """))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.type").value("/problems/invalid-price-list"));
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2B_GOLD","lines":[{"sku":"CHAIR-001","quantity":1}]}
                    """))
            .andExpect(jsonPath("$.lines[0].unitPriceNet").value(84.90));
    }

    @Test
    void quotesGoldTierWithNetAndVat() throws Exception {
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2B_GOLD","lines":[{"sku":"CHAIR-001","quantity":25}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customerGroup").value("B2B_GOLD"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.lines[0].unitPriceNet").value(74.90))
            .andExpect(jsonPath("$.lines[0].lineTotalNet").value(1872.50))
            .andExpect(jsonPath("$.lines[0].lineTotalVat").value(505.58))
            .andExpect(jsonPath("$.lines[0].lineTotalGross").doesNotExist())
            .andExpect(jsonPath("$.lines[0].appliedRule").value("TIER_MIN_QTY_20"))
            .andExpect(jsonPath("$.totalNet").value(1872.50))
            .andExpect(jsonPath("$.totalVat").value(505.58))
            .andExpect(jsonPath("$.totalGross").value(2378.08))
            .andExpect(jsonPath("$.quotedAt").exists());
    }

    @Test
    void quotesB2cListPricesWithGrossAmountsForMultipleLines() throws Exception {
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2C","lines":[
                      {"sku":"CHAIR-001","quantity":1},
                      {"sku":"LAMP-002","quantity":2}
                    ]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines.length()").value(2))
            .andExpect(jsonPath("$.lines[0].lineTotalGross").value(126.87))
            .andExpect(jsonPath("$.lines[0].lineTotalVat").doesNotExist())
            .andExpect(jsonPath("$.lines[0].appliedRule").value("LIST_PRICE"))
            .andExpect(jsonPath("$.lines[1].lineTotalGross").value(114.05))
            .andExpect(jsonPath("$.totalNet").value(189.70))
            .andExpect(jsonPath("$.totalVat").value(51.22))
            .andExpect(jsonPath("$.totalGross").value(240.92));
    }

    @Test
    void reportsEveryUnknownSkuAsUnprocessableContent() throws Exception {
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2B_STANDARD","lines":[
                      {"sku":"MISSING-B","quantity":1},
                      {"sku":"CHAIR-001","quantity":1},
                      {"sku":"MISSING-A","quantity":2}
                    ]}
                    """))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.type").value("/problems/unknown-skus"))
            .andExpect(jsonPath("$.skus[0]").value("MISSING-A"))
            .andExpect(jsonPath("$.skus[1]").value("MISSING-B"));
    }

    @Test
    void rejectsInvalidQuoteRequestBeforePricing() throws Exception {
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2C","lines":[{"sku":"CHAIR-001","quantity":0}]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("/problems/validation"));
    }

    @Test
    void rejectsQuoteWithMoreThanHundredLines() throws Exception {
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(quoteBody(101)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsPriceListWithMoreThanHundredTiers() throws Exception {
        mvc.perform(put("/api/v1/products/SIZE-TEST-NONE/prices").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tiersBody(101)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedQuoteBodyBeforeAuthentication() throws Exception {
        mvc.perform(post("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(70_000)))
            .andExpect(status().is(413))
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.type").value("/problems/payload-too-large"));
    }

    private static String quoteBody(int lines) {
        return java.util.stream.IntStream.rangeClosed(1, lines)
            .mapToObj(i -> "{\"sku\":\"SKU-%03d\",\"quantity\":1}".formatted(i))
            .collect(java.util.stream.Collectors.joining(",", "{\"customerGroup\":\"B2B_GOLD\",\"lines\":[", "]}"));
    }

    private static String tiersBody(int tiers) {
        return java.util.stream.IntStream.rangeClosed(1, tiers)
            .mapToObj(i -> "{\"minQty\":%d,\"unitPriceNet\":10.00,\"validFrom\":\"2020-01-01\"}".formatted(i))
            .collect(java.util.stream.Collectors.joining(",", "{\"customerGroup\":\"B2B_GOLD\",\"tiers\":[", "]}"));
    }

    @Test
    void rejectsDuplicateSkusInQuote() throws Exception {
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2B_GOLD","lines":[
                      {"sku":"CHAIR-001","quantity":10},
                      {"sku":"CHAIR-001","quantity":10}
                    ]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.violations.distinctSkus").value("SKUs must be unique"));
    }

    @Test
    void rejectsQuantityAboveLimitInQuote() throws Exception {
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2B_GOLD","lines":[{"sku":"CHAIR-001","quantity":10001}]}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownCustomerGroupBeforeCaching() throws Exception {
        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"NOPE","lines":[{"sku":"CHAIR-001","quantity":1}]}
                    """))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.type").value("/problems/unknown-customer-group"));
        verify(priceListEntryRepository, never()).findAllByCustomerGroupCodeAndSkuIn(eq("NOPE"), anyCollection());
    }

    @Test
    void rejectsPriceListForUnknownCustomerGroup() throws Exception {
        mvc.perform(put("/api/v1/products/CHAIR-001/prices").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"NOPE","tiers":[{"minQty":1,"unitPriceNet":10.00,"validFrom":"2020-01-01"}]}
                    """))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.type").value("/problems/unknown-customer-group"));
    }

    @MockitoSpyBean
    private PriceTierCache priceTierCache;

    @Test
    void quoteDoesNotHoldADatabaseTransactionWhileUsingTheCache() throws Exception {
        var transactionActive = new AtomicBoolean(true);
        doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(priceTierCache).find(any(), any());

        mvc.perform(post("/api/v1/quotes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_pricing.quote")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2B_GOLD","lines":[{"sku":"CHAIR-001","quantity":1}]}
                    """))
            .andExpect(status().isOk());

        assertThat(transactionActive).isFalse();
    }
}
