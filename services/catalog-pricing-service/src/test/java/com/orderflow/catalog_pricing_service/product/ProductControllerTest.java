package com.orderflow.catalog_pricing_service.product;

import com.orderflow.catalog_pricing_service.api.ApiExceptionHandler;
import com.orderflow.catalog_pricing_service.product.api.ProductController;
import com.orderflow.catalog_pricing_service.product.application.ProductCommandService;
import com.orderflow.catalog_pricing_service.product.application.ProductQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductControllerTest {

    private final ProductQueryService queries = mock(ProductQueryService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new ProductController(queries, mock(ProductCommandService.class)))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void unreachableDatabaseIsServiceUnavailable() throws Exception {
        when(queries.findBySku("CHAIR-001")).thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager"));

        mvc.perform(get("/api/v1/products/{sku}", "CHAIR-001"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(header().string("Retry-After", "5"))
            .andExpect(jsonPath("$.type").value("/problems/database-unavailable"));
    }
}
