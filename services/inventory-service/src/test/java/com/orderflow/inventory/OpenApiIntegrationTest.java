package com.orderflow.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, TestJwtConfiguration.class})
class OpenApiIntegrationTest {

    private static final String[] PATHS = {"/api/v1/stock/{sku}", "/api/v1/reservations"};

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void publishesApiDocsWithoutTokenListingEveryEndpoint() throws Exception {
        String body = mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        var docs = json.readTree(body);
        assertThat(docs.path("paths").propertyNames()).contains(PATHS);
        assertThat(docs.path("components").path("securitySchemes").path("bearer-jwt").path("scheme").asString())
            .isEqualTo("bearer");
    }

    @Test
    void servesSwaggerUiWithoutToken() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk());
    }
}
