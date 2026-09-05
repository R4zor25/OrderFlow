package com.orderflow.order.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.application.OrderDetails;
import com.orderflow.order.application.OrderQueryService;
import com.orderflow.order.config.PricingResilienceProperties;
import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderReadControllerTest {

    private final OrderQueryService service = mock(OrderQueryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var pricing = new PricingResilienceProperties(
            2, Duration.ofMillis(100), 2, 0.2, 10, 10, 50, Duration.ofSeconds(10), 2);
        mvc = MockMvcBuilders.standaloneSetup(new OrderReadController(service))
            .setControllerAdvice(new ApiExceptionHandler(pricing))
            .build();
    }

    @Test
    void getsOrderWithLines() throws Exception {
        OrderDetails order = order();
        when(service.get(order.id())).thenReturn(order);

        mvc.perform(get("/api/v1/orders/{id}", order.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(order.id().toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.lines[0].sku").value("CHAIR-001"))
            .andExpect(jsonPath("$.lines[0].quantity").value(2));
    }

    @Test
    void listsFilteredPageWithMetadata() throws Exception {
        OrderDetails order = order();
        when(service.list(order.customerId(), OrderStatus.PENDING, 1, 5))
            .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(1, 5), 6));

        mvc.perform(get("/api/v1/orders")
                .param("customerId", order.customerId().toString())
                .param("status", "PENDING")
                .param("page", "1")
                .param("size", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(order.id().toString()))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.totalElements").value(6))
            .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void reportsMissingOrderAsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new OrderNotFoundException("Order not found: " + id));

        mvc.perform(get("/api/v1/orders/{id}", id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("/problems/order-not-found"));
    }

    private static OrderDetails order() {
        return new OrderDetails(
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            OrderStatus.PENDING, "EUR", new BigDecimal("169.80"),
            new BigDecimal("45.85"), new BigDecimal("215.65"),
            Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-01T10:00:00Z"),
            null, List.of(new OrderDetails.Line(UUID.randomUUID(), "CHAIR-001", 2,
                new BigDecimal("84.90"), new BigDecimal("169.80"))));
    }
}
