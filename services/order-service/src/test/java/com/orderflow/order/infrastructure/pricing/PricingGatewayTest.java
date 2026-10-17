package com.orderflow.order.infrastructure.pricing;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.orderflow.order.config.PricingClientConfiguration;
import com.orderflow.order.config.PricingClientProperties;
import com.orderflow.order.config.PricingResilienceConfiguration;
import com.orderflow.order.config.PricingResilienceProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingGatewayTest {

    private WireMockServer server;
    private PricingGateway gateway;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        var properties = new PricingClientProperties(
            URI.create(server.baseUrl()), Duration.ofSeconds(1), Duration.ofSeconds(2)
        );
        var configuration = new PricingClientConfiguration();
        server.stubFor(post("/token").willReturn(okJson(
            "{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        var registration = ClientRegistration.withRegistrationId(PricingClientConfiguration.PRICING_REGISTRATION_ID)
            .clientId("order-service").clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope("pricing.quote").tokenUri(server.baseUrl() + "/token").build();
        var registrations = new InMemoryClientRegistrationRepository(registration);
        var clients = new InMemoryOAuth2AuthorizedClientService(registrations);
        var manager = configuration.pricingAuthorizedClientManager(registrations, clients, properties);
        RestClient restClient = configuration.pricingRestClient(RestClient.builder(), properties, manager, clients);
        PricingClient client = configuration.pricingClient(restClient);
        var resilience = new PricingResilienceConfiguration();
        var resilienceProperties = new PricingResilienceProperties(
            2, Duration.ofMillis(10), 2, 0.2, 10, 10, 50, Duration.ofSeconds(10), 2);
        circuitBreaker = resilience.pricingCircuitBreaker(
            resilience.pricingCircuitBreakerRegistry(resilienceProperties));
        gateway = new PricingGateway(client,
            resilience.pricingRetry(resilience.pricingRetryRegistry(resilienceProperties)), circuitBreaker);
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
        server.verify(1, postRequestedFor(urlEqualTo("/api/v1/quotes")));
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void translatesServerFailureToPricingUnavailable() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> gateway.quote(sampleRequest()))
            .isInstanceOf(PricingUnavailableException.class);
        server.verify(2, postRequestedFor(urlEqualTo("/api/v1/quotes")));
    }

    @Test
    void retriesServerFailureOnceAndCountsFinalSuccessOnce() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .inScenario("temporary failure")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("recovered"));
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .inScenario("temporary failure")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("""
                    {"customerGroup":"B2B_GOLD","currency":"EUR","lines":[],
                     "totalNet":0,"totalVat":0,"totalGross":0,
                     "quotedAt":"2026-10-01T10:15:30Z"}
                    """)));

        assertThat(gateway.quote(sampleRequest()).currency()).isEqualTo("EUR");
        server.verify(2, postRequestedFor(urlEqualTo("/api/v1/quotes")));
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void opensAfterTenFailedQuotesAndRejectsNextWithoutHttp() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .willReturn(aResponse().withStatus(503)));

        for (int call = 0; call < 10; call++) {
            // The breaker must open on the stubbed server errors; any other failure is reported with its cause.
            assertThatThrownBy(() -> gateway.quote(sampleRequest()))
                .isInstanceOf(PricingUnavailableException.class)
                .hasRootCauseInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(10);
        assertThatThrownBy(() -> gateway.quote(sampleRequest()))
            .isInstanceOf(CallNotPermittedException.class);
        server.verify(20, postRequestedFor(urlEqualTo("/api/v1/quotes")));
    }

    @Test
    void closesAfterTwoSuccessfulHalfOpenQuotes() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .willReturn(aResponse().withStatus(503)));
        for (int call = 0; call < 10; call++) {
            // The breaker must open on the stubbed server errors; any other failure is reported with its cause.
            assertThatThrownBy(() -> gateway.quote(sampleRequest()))
                .isInstanceOf(PricingUnavailableException.class)
                .hasRootCauseInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        server.resetAll();
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("""
                    {"customerGroup":"B2B_GOLD","currency":"EUR","lines":[],
                     "totalNet":0,"totalVat":0,"totalGross":0,
                     "quotedAt":"2026-10-01T10:15:30Z"}
                    """)));
        circuitBreaker.transitionToHalfOpenState();

        assertThat(gateway.quote(sampleRequest()).currency()).isEqualTo("EUR");
        assertThat(gateway.quote(sampleRequest()).currency()).isEqualTo("EUR");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        server.verify(2, postRequestedFor(urlEqualTo("/api/v1/quotes")));
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
        server.verify(1, postRequestedFor(urlEqualTo("/api/v1/quotes")));
    }

    @Test
    void readTimeoutStopsWaitingForSlowTokenEndpoint() {
        server.stubFor(post("/token").willReturn(okJson(
            "{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")
            .withFixedDelay(5000)));
        long started = System.nanoTime();

        assertThatThrownBy(() -> gateway.quote(sampleRequest()))
            .isInstanceOf(PricingUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        server.verify(0, postRequestedFor(urlEqualTo("/api/v1/quotes")));
    }

    @Test
    void sendsBearerTokenToCatalog() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes")).willReturn(aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"customerGroup":"B2B_GOLD","currency":"EUR","lines":[],
                 "totalNet":0,"totalVat":0,"totalGross":0,
                 "quotedAt":"2026-10-01T10:15:30Z"}
                """)));

        gateway.quote(sampleRequest());

        server.verify(postRequestedFor(urlEqualTo("/api/v1/quotes"))
            .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    @Test
    void treatsCatalogUnauthorizedAsUnavailableWithoutRetry() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> gateway.quote(sampleRequest())).isInstanceOf(PricingUnavailableException.class);

        server.verify(1, postRequestedFor(urlEqualTo("/api/v1/quotes")));
    }

    @Test
    void treatsCatalogForbiddenAsUnavailableWithoutRetry() {
        server.stubFor(post(urlEqualTo("/api/v1/quotes")).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> gateway.quote(sampleRequest())).isInstanceOf(PricingUnavailableException.class);

        server.verify(1, postRequestedFor(urlEqualTo("/api/v1/quotes")));
    }

    @Test
    void fetchesNewTokenAfterCatalogUnauthorized() {
        assertTokenEvictedAfterCatalogStatus(401);
    }

    @Test
    void fetchesNewTokenAfterCatalogForbidden() {
        assertTokenEvictedAfterCatalogStatus(403);
    }

    private void assertTokenEvictedAfterCatalogStatus(int status) {
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .inScenario("rejected token")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(status))
            .willSetStateTo("accepted"));
        server.stubFor(post(urlEqualTo("/api/v1/quotes"))
            .inScenario("rejected token")
            .whenScenarioStateIs("accepted")
            .willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("""
                    {"customerGroup":"B2B_GOLD","currency":"EUR","lines":[],
                     "totalNet":0,"totalVat":0,"totalGross":0,
                     "quotedAt":"2026-10-01T10:15:30Z"}
                    """)));

        assertThatThrownBy(() -> gateway.quote(sampleRequest())).isInstanceOf(PricingUnavailableException.class);
        assertThat(gateway.quote(sampleRequest()).currency()).isEqualTo("EUR");

        server.verify(2, postRequestedFor(urlEqualTo("/token")));
    }

    private static QuoteRequest sampleRequest() {
        return new QuoteRequest("B2B_GOLD", List.of(new QuoteLineRequest("CHAIR-001", 25)));
    }
}
