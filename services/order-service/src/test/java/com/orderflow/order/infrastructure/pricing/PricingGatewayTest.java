package com.orderflow.order.infrastructure.pricing;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.orderflow.order.config.PricingClientConfiguration;
import com.orderflow.order.config.PricingClientProperties;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingGatewayTest {

    private WireMockServer server;
    private PricingGateway gateway;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        var properties = new PricingClientProperties(
            URI.create(server.baseUrl()), Duration.ofSeconds(1), Duration.ofSeconds(2)
        );
        var configuration = new PricingClientConfiguration();
        RestClient restClient = configuration.pricingRestClient(RestClient.builder(), properties);
        PricingClient client = configuration.pricingClient(restClient);
        gateway = new PricingGateway(client);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void sendsQuoteRequestAndReadsCompleteResponse() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .withRequestBody(equalToJson("""
                {"customerGroup":"B2B_GOLD","lines":[{"sku":"CHAIR-001","quantity":25}]}
                """))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                {"customerGroup":"B2B_GOLD","currency":"EUR",
                 "lines":[{"sku":"CHAIR-001","quantity":25,"unitPriceNet":74.90,
                           "lineTotalNet":1872.50,"lineTotalVat":505.58,
                           "appliedRule":"TIER_MIN_QTY_20"}],
                 "totalNet":1872.50,"totalVat":505.58,"totalGross":2378.08,
                 "quotedAt":"2026-10-01T10:15:30Z"}
                """)));

        QuoteResponse quote = gateway.quote(new QuoteRequest("B2B_GOLD",
            List.of(new QuoteLineRequest("CHAIR-001", 25))));

        assertThat(quote.currency()).isEqualTo("EUR");
        assertThat(quote.totalGross()).isEqualByComparingTo(new BigDecimal("2378.08"));
        assertThat(quote.lines()).hasSize(1);
        assertThat(quote.lines().getFirst().unitPriceNet()).isEqualByComparingTo("74.90");
        assertThat(quote.lines().getFirst().appliedRule()).isEqualTo("TIER_MIN_QTY_20");
        assertThat(quote.quotedAt()).isEqualTo(Instant.parse("2026-10-01T10:15:30Z"));
        server.verify(1, postRequestedFor(urlEqualTo("/api/v1/quotes")));
    }

    @Test
    void keepsClientValidationFailureSeparateFromAvailabilityFailure() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .willReturn(aResponse().withStatus(422)));

        assertThatThrownBy(() -> gateway.quote(sampleRequest()))
            .isInstanceOf(PricingRequestRejectedException.class)
            .satisfies(error -> assertThat(((PricingRequestRejectedException) error).getStatus().value())
                .isEqualTo(422));
    }

    @Test
    void translatesServerFailureToPricingUnavailable() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> gateway.quote(sampleRequest()))
            .isInstanceOf(PricingUnavailableException.class);
    }

    @Test
    void translatesConnectionFailureToPricingUnavailable() {
        server.stop();

        assertThatThrownBy(() -> gateway.quote(sampleRequest()))
            .isInstanceOf(PricingUnavailableException.class);
    }

    @Test
    void readTimeoutStopsWaitingForSlowPricingResponse() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .willReturn(aResponse().withFixedDelay(5000).withHeader("Content-Type", "application/json")
                .withBody("{}")));
        long started = System.nanoTime();

        assertThatThrownBy(() -> gateway.quote(sampleRequest()))
            .isInstanceOf(PricingUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    private static QuoteRequest sampleRequest() {
        return new QuoteRequest("B2B_GOLD", List.of(new QuoteLineRequest("CHAIR-001", 25)));
    }
}
