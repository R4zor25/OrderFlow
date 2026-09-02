package com.orderflow.catalog_pricing_service.price;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class QuoteApiTest {

    private final MockMvc mvc;

    @Autowired
    QuoteApiTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void quotesGoldTierWithNetAndVat() throws Exception {
        mvc.perform(post("/api/v1/quotes")
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
        mvc.perform(post("/api/v1/quotes")
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
        mvc.perform(post("/api/v1/quotes")
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
        mvc.perform(post("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerGroup":"B2C","lines":[{"sku":"CHAIR-001","quantity":0}]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("/problems/validation"));
    }
}
