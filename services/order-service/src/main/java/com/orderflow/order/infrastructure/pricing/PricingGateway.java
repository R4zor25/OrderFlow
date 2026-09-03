package com.orderflow.order.infrastructure.pricing;

import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
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
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new PricingRequestRejectedException(exception.getStatusCode(), exception);
            }
            throw new PricingUnavailableException("Pricing service returned an error", exception);
        } catch (RestClientException exception) {
            throw new PricingUnavailableException("Pricing service could not be reached", exception);
        }
    }
}
