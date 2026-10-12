package com.orderflow.catalog_pricing_service.product;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.RedisTestConfiguration;
import com.orderflow.catalog_pricing_service.product.application.ProductQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class ProductPagingHttpIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoSpyBean
    private ProductQueryService queries;

    @Test
    void acceptsTheLargestOffsetTheDatabaseQueryCanTake() throws Exception {
        // 21 474 836 × 100 = 2 147 483 600, the last page start below Integer.MAX_VALUE.
        assertThat(get("/api/v1/products?page=21474836&size=100").statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsAnOffsetBeyondTheDatabaseLimitAsABadRequest() throws Exception {
        for (String page : new String[] {"21474837", "2147483647"}) {
            var response = get("/api/v1/products?page=" + page + "&size=100");
            assertThat(response.statusCode()).as("page " + page).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("application/problem+json"));
        }
    }

    @Test
    void answersAnUnexpectedErrorOnAPublicEndpointWithServerErrorNotUnauthorized() throws Exception {
        doThrow(new IllegalStateException("unexpected")).when(queries).findBySku("BROKEN-001");

        assertThat(get("/api/v1/products/BROKEN-001").statusCode()).isEqualTo(500);
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        }
    }
}
