package com.orderflow.order.infrastructure.pricing;

import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PricingGateway {

    private final PricingClient client;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public PricingGateway(PricingClient client, Retry retry, CircuitBreaker circuitBreaker) {
        this.client = client;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
    }

    public QuoteResponse quote(QuoteRequest request) {
        Supplier<QuoteResponse> retried = Retry.decorateSupplier(retry, () -> quoteOnce(request));
        return circuitBreaker.executeSupplier(retried);
    }

    private QuoteResponse quoteOnce(QuoteRequest request) {
        try {
            return client.quote(request);
        } catch (OAuth2AuthorizationException exception) {
            throw new PricingUnavailableException("Pricing credentials unavailable", exception);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new PricingUnavailableException("Pricing rejected the service credentials", exception);
            }
            if (exception.getStatusCode().is4xxClientError()) {
                throw new PricingRequestRejectedException(exception.getStatusCode(), exception);
            }
            throw new PricingUnavailableException("Pricing service returned an error", exception);
        } catch (RestClientException exception) {
            throw new PricingUnavailableException("Pricing service could not be reached", exception);
        }
    }
}
