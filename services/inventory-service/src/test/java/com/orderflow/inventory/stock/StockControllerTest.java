package com.orderflow.inventory.stock;

import com.orderflow.inventory.platform.web.ApiExceptionHandler;
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

class StockControllerTest {

    private final StockService stock = mock(StockService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StockController(stock))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void unreachableDatabaseIsServiceUnavailable() throws Exception {
        when(stock.get("CHAIR-001")).thenThrow(new CannotCreateTransactionException("Could not open JDBC connection"));

        mvc.perform(get("/api/v1/stock/{sku}", "CHAIR-001"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(header().string("Retry-After", "5"))
            .andExpect(jsonPath("$.type").value("/problems/database-unavailable"));
    }
}
