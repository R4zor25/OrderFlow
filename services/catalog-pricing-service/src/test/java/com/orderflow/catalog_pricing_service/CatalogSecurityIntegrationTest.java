package com.orderflow.catalog_pricing_service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.orderflow.catalog_pricing_service.product.infrastructure.ProductRepository;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class, KeycloakTestConfiguration.class})
class CatalogSecurityIntegrationTest {

    private static final String PRODUCT = """
        {"sku":"SEC-TEST-001","name":"Security test","category":"TEST","listPriceNet":10.00,"vatRate":0.27}
        """;
    private static final String PRICES = """
        {"customerGroup":"B2B_GOLD","tiers":[{"minQty":1,"unitPriceNet":84.90,"validFrom":"2020-01-01"}]}
        """;
    private static final String QUOTE = """
        {"customerGroup":"B2C","lines":[{"sku":"CHAIR-001","quantity":1}]}
        """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductRepository products;

    @AfterEach
    void removeProduct() {
        products.findBySku("SEC-TEST-001").ifPresent(products::delete);
    }

    @Test
    void productReadsArePublic() throws Exception {
        mvc.perform(get("/api/v1/products/CHAIR-001")).andExpect(status().isOk());
    }

    @Test
    void productWriteWithoutTokenIsUnauthorizedProblem() throws Exception {
        mvc.perform(json(post("/api/v1/products"), PRODUCT, null))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
            .andExpect(header().string("Content-Type", "application/problem+json"))
            .andExpect(jsonPath("$.type").value("/problems/unauthorized"));
    }

    @Test
    void onlyAdminCreatesProducts() throws Exception {
        mvc.perform(json(post("/api/v1/products"), PRODUCT, KeycloakTokens.password("standard")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.type").value("/problems/forbidden"));
        mvc.perform(json(post("/api/v1/products"), PRODUCT, KeycloakTokens.password("admin")))
            .andExpect(status().isCreated());
    }

    @Test
    void onlyAdminReplacesPrices() throws Exception {
        mvc.perform(json(put("/api/v1/products/CHAIR-001/prices"), PRICES, KeycloakTokens.password("gold")))
            .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/v1/products/CHAIR-001/prices"), PRICES, KeycloakTokens.password("admin")))
            .andExpect(status().isNoContent());
    }

    @Test
    void onlyPricingScopeQuotes() throws Exception {
        mvc.perform(json(post("/api/v1/quotes"), QUOTE, KeycloakTokens.password("standard")))
            .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/quotes"), QUOTE, KeycloakTokens.clientCredentials()))
            .andExpect(status().isOk());
    }

    @Test
    void rejectsTokenSignedByAnotherKey() throws Exception {
        mvc.perform(json(post("/api/v1/products"), PRODUCT, TestJwts.withRoles("ADMIN")))
            .andExpect(status().isUnauthorized());
    }

    private static RequestBuilder json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                       String body, String token) {
        request.contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request;
    }
}
