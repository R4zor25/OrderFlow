package com.orderflow.inventory.stock;

import com.orderflow.inventory.PostgresTestConfiguration;
import com.orderflow.inventory.TestJwtConfiguration;
import com.orderflow.inventory.TestJwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, TestJwtConfiguration.class})
class StockApiIntegrationTest {

    private static final String SKU = "STOCK-API-001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeStock() {
        jdbc.update("delete from inventory.stock_item where sku = ?", SKU);
    }

    @Test
    void returnsStockLevelForAdmin() throws Exception {
        insertStock(20, 5, 3, false);

        mvc.perform(get("/api/v1/stock/{sku}", SKU).header("Authorization", admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sku").value(SKU))
            .andExpect(jsonPath("$.onHand").value(20))
            .andExpect(jsonPath("$.reserved").value(5))
            .andExpect(jsonPath("$.available").value(15));
    }

    @Test
    void rejectsStockReadWithoutTokenOrAdminRole() throws Exception {
        insertStock(20, 5, 3, false);

        mvc.perform(get("/api/v1/stock/{sku}", SKU))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("/problems/unauthorized"));
        mvc.perform(get("/api/v1/stock/{sku}", SKU).header("Authorization", "Bearer " + TestJwts.withRoles("CUSTOMER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.type").value("/problems/forbidden"));
        mvc.perform(put("/api/v1/stock/{sku}", SKU).header("Authorization", "Bearer " + TestJwts.withRoles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"onHand\":30}"))
            .andExpect(status().isForbidden());
        assertThat(onHand()).isEqualTo(20);
    }

    @Test
    void returns404ForUnknownSku() throws Exception {
        mvc.perform(get("/api/v1/stock/{sku}", "NO-SUCH-SKU").header("Authorization", admin()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("/problems/stock-not-found"));
        mvc.perform(setOnHand("NO-SUCH-SKU", "{\"onHand\":5}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("/problems/stock-not-found"));
    }

    @Test
    void setsOnHandAndResetsLowStockFlagAboveThreshold() throws Exception {
        insertStock(4, 2, 3, true);

        mvc.perform(setOnHand(SKU, "{\"onHand\":6}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onHand").value(6))
            .andExpect(jsonPath("$.reserved").value(2))
            .andExpect(jsonPath("$.available").value(4));

        assertThat(onHand()).isEqualTo(6);
        assertThat(lowStockNotified()).isFalse();
    }

    @Test
    void keepsLowStockFlagWhenStillAtOrBelowThreshold() throws Exception {
        insertStock(4, 2, 3, true);

        mvc.perform(setOnHand(SKU, "{\"onHand\":5}")).andExpect(status().isOk());

        assertThat(onHand()).isEqualTo(5);
        assertThat(lowStockNotified()).isTrue();
    }

    @Test
    void rejectsOnHandBelowReservedWith409() throws Exception {
        insertStock(10, 5, 3, false);

        mvc.perform(setOnHand(SKU, "{\"onHand\":4}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("/problems/stock-below-reserved"))
            .andExpect(jsonPath("$.detail").value("onHand 4 is below the 5 units already reserved"));

        assertThat(onHand()).isEqualTo(10);
    }

    @Test
    void rejectsInvalidOnHandWith422() throws Exception {
        insertStock(10, 0, 3, false);

        for (String body : new String[] {"{\"onHand\":-1}", "{}", "{\"onHand\":1000001}"}) {
            mvc.perform(setOnHand(SKU, body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        }
        assertThat(onHand()).isEqualTo(10);
    }

    private org.springframework.test.web.servlet.RequestBuilder setOnHand(String sku, String body) {
        return put("/api/v1/stock/{sku}", sku).header("Authorization", admin())
            .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String admin() {
        return "Bearer " + TestJwts.withRoles("ADMIN");
    }

    private void insertStock(int onHand, int reserved, int threshold, boolean notified) {
        jdbc.update("""
            insert into inventory.stock_item
                (sku, on_hand, reserved, low_stock_threshold, low_stock_notified, version, updated_at)
            values (?, ?, ?, ?, ?, 0, now())
            """, SKU, onHand, reserved, threshold, notified);
    }

    private int onHand() {
        return jdbc.queryForObject("select on_hand from inventory.stock_item where sku = ?", Integer.class, SKU);
    }

    private boolean lowStockNotified() {
        return jdbc.queryForObject("select low_stock_notified from inventory.stock_item where sku = ?", Boolean.class, SKU);
    }
}
