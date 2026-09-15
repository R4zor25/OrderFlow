package com.orderflow.catalog_pricing_service.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.product.infrastructure.ProductRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ProductWriteApiTest {

    private final MockMvc mvc;
    private final ProductRepository repository;

    @Autowired
    ProductWriteApiTest(MockMvc mvc, ProductRepository repository) {
        this.mvc = mvc;
        this.repository = repository;
    }

    @AfterEach
    void removeCreatedProduct() {
        repository.findBySku("TEST-NEW-001").ifPresent(repository::delete);
    }

    @Test
    void createsProductAndReturnsLocation() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validProduct()))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/products/TEST-NEW-001"))
            .andExpect(jsonPath("$.sku").value("TEST-NEW-001"))
            .andExpect(jsonPath("$.active").value(true));

        mvc.perform(get("/api/v1/products/TEST-NEW-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("New Test Product"));
    }

    @Test
    void rejectsBlankSkuAsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validProduct().replace("TEST-NEW-001", "  ")))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Content-Type", "application/problem+json"))
            .andExpect(jsonPath("$.type").value("/problems/validation"))
            .andExpect(jsonPath("$.violations.sku").exists());
    }

    @Test
    void rejectsNonPositivePriceAsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validProduct().replace("19.90", "0.00")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("/problems/validation"))
            .andExpect(jsonPath("$.violations.listPriceNet").exists());
    }

    @Test
    void rejectsSkuWithPathSeparator() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validProduct().replace("TEST-NEW-001", "TEST/NEW")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.violations.sku").exists());
    }

    @Test
    void rejectsNonNumericPageAsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/products").param("page", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("/problems/validation"));
    }

    @Test
    void rejectsMalformedJsonAsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("/problems/validation"));
    }

    @Test
    void rejectsDuplicateSkuAsConflict() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validProduct().replace("TEST-NEW-001", "CHAIR-001")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("/problems/sku-conflict"))
            .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void rejectsVatRateOutsideDomainRange() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validProduct().replace("0.27", "1.50")))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.type").value("/problems/invalid-product"))
            .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void returnsProblemDetailForUnknownProduct() throws Exception {
        mvc.perform(get("/api/v1/products/UNKNOWN"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("/problems/not-found"))
            .andExpect(jsonPath("$.status").value(404));
    }

    private String validProduct() {
        return """
            {
              "sku": "TEST-NEW-001",
              "name": "New Test Product",
              "category": "OFFICE",
              "listPriceNet": 19.90,
              "vatRate": 0.27
            }
            """;
    }
}
