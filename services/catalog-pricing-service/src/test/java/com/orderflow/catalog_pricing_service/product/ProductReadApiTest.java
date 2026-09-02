package com.orderflow.catalog_pricing_service.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ProductReadApiTest {

    private final MockMvc mvc;

    @Autowired
    ProductReadApiTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void listsProductsByCategoryWithStablePagination() throws Exception {
        mvc.perform(get("/api/v1/products")
                .param("category", "FURNITURE")
                .param("page", "0")
                .param("size", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].sku").value("CHAIR-001"))
            .andExpect(jsonPath("$.items[2].sku").value("CHAIR-003"))
            .andExpect(jsonPath("$.totalElements").value(8))
            .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void returnsProductBySku() throws Exception {
        mvc.perform(get("/api/v1/products/CHAIR-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sku").value("CHAIR-001"))
            .andExpect(jsonPath("$.listPriceNet").value(99.90))
            .andExpect(jsonPath("$.category").value("FURNITURE"));
    }

    @Test
    void returnsNotFoundForUnknownSku() throws Exception {
        mvc.perform(get("/api/v1/products/UNKNOWN"))
            .andExpect(status().isNotFound());
    }

    @Test
    void capsRequestedPageSize() throws Exception {
        mvc.perform(get("/api/v1/products").param("size", "500"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.size").value(100))
            .andExpect(jsonPath("$.totalElements").value(20));
    }

    @Test
    void rejectsNegativePageNumber() throws Exception {
        mvc.perform(get("/api/v1/products").param("page", "-1"))
            .andExpect(status().isBadRequest());
    }
}
