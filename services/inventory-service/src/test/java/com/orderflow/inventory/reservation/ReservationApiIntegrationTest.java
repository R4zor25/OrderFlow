package com.orderflow.inventory.reservation;

import java.util.UUID;

import com.orderflow.inventory.PostgresTestConfiguration;
import com.orderflow.inventory.TestJwtConfiguration;
import com.orderflow.inventory.TestJwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, TestJwtConfiguration.class})
class ReservationApiIntegrationTest {

    private static final String SKU_A = "RES-API-A";
    private static final String SKU_B = "RES-API-B";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeRows() {
        jdbc.update("delete from inventory.reservation where sku in (?, ?)", SKU_A, SKU_B);
        jdbc.update("delete from inventory.stock_item where sku in (?, ?)", SKU_A, SKU_B);
    }

    @Test
    void listsReservationsOfAnOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        for (String sku : new String[] {SKU_B, SKU_A}) {
            jdbc.update("""
                insert into inventory.stock_item
                    (sku, on_hand, reserved, low_stock_threshold, low_stock_notified, version, updated_at)
                values (?, 10, 0, 1, false, 0, now())
                """, sku);
        }
        insertReservation(orderId, SKU_B, 2, "RESERVED");
        insertReservation(orderId, SKU_A, 1, "RELEASED");
        insertReservation(UUID.randomUUID(), SKU_A, 3, "RESERVED");

        mvc.perform(get("/api/v1/reservations").param("orderId", orderId.toString()).header("Authorization", admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].sku").value(SKU_A))
            .andExpect(jsonPath("$[0].quantity").value(1))
            .andExpect(jsonPath("$[0].status").value("RELEASED"))
            .andExpect(jsonPath("$[0].createdAt").exists())
            .andExpect(jsonPath("$[1].sku").value(SKU_B))
            .andExpect(jsonPath("$[1].status").value("RESERVED"));
    }

    @Test
    void returnsEmptyListForOrderWithoutReservations() throws Exception {
        mvc.perform(get("/api/v1/reservations").param("orderId", UUID.randomUUID().toString())
                .header("Authorization", admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void rejectsReservationQueryWithoutOrderIdWith400() throws Exception {
        mvc.perform(get("/api/v1/reservations").header("Authorization", admin()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("/problems/bad-request"));
        mvc.perform(get("/api/v1/reservations").param("orderId", "not-a-uuid").header("Authorization", admin()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("/problems/bad-request"));
    }

    @Test
    void rejectsReservationQueryForNonAdmin() throws Exception {
        mvc.perform(get("/api/v1/reservations").param("orderId", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + TestJwts.withRoles("CUSTOMER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reservations").param("orderId", UUID.randomUUID().toString()))
            .andExpect(status().isUnauthorized());
    }

    private void insertReservation(UUID orderId, String sku, int quantity, String status) {
        jdbc.update("""
            insert into inventory.reservation (id, order_id, sku, quantity, status, created_at)
            values (?, ?, ?, ?, ?, now())
            """, UUID.randomUUID(), orderId, sku, quantity, status);
    }

    private static String admin() {
        return "Bearer " + TestJwts.withRoles("ADMIN");
    }
}
