package com.orderflow.catalog_pricing_service.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.orderflow.catalog_pricing_service.CatalogPricingServiceApplication;
import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.RedisTestConfiguration;
import com.orderflow.catalog_pricing_service.TestJwtConfiguration;
import com.orderflow.catalog_pricing_service.TestJwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CatalogTracingIntegrationTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String PARENT_SPAN_ID = "0123456789abcdef";

    @Test
    void quoteLogsContinueIncomingTraceAndRejectMalformedContextWithoutFailingRequest(CapturedOutput output)
            throws Exception {
        try (var context = new SpringApplicationBuilder(CatalogPricingServiceApplication.class,
                PostgresTestConfiguration.class, RedisTestConfiguration.class, TestJwtConfiguration.class)
                .run("--server.port=0", "--management.tracing.sampling.probability=1")) {
            int port = context.getEnvironment().getProperty("local.server.port", Integer.class);
            var json = context.getBean(ObjectMapper.class);
            try (var client = HttpClient.newHttpClient()) {
                assertThat(quote(client, port, "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01").statusCode())
                    .isEqualTo(200);
                var entries = quoteLogEntries(output, json);
                assertThat(entries).hasSize(1);
                assertThat(entries.getFirst().path("traceId").asString()).isEqualTo(TRACE_ID);
                assertThat(entries.getFirst().path("spanId").asString())
                    .matches("[0-9a-f]{16}").isNotEqualTo(PARENT_SPAN_ID);

                assertThat(quote(client, port, "not-a-traceparent").statusCode()).isEqualTo(200);
                entries = quoteLogEntries(output, json);
                assertThat(entries).hasSize(2);
                assertThat(entries.getLast().path("traceId").asString())
                    .matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID);
            }
        }
    }

    private HttpResponse<String> quote(HttpClient client, int port, String traceparent) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/quotes"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("traceparent", traceparent)
            .header("Authorization", "Bearer " + TestJwts.withScope("pricing.quote"))
            .POST(HttpRequest.BodyPublishers.ofString("""
                {"customerGroup":"B2B_GOLD","lines":[{"sku":"CHAIR-001","quantity":1}]}
                """))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<JsonNode> quoteLogEntries(CapturedOutput output, ObjectMapper json) {
        return output.getOut().lines()
            .filter(line -> line.startsWith("{") && line.contains("Quote calculated"))
            .map(json::readTree)
            .toList();
    }
}
