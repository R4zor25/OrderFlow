package com.orderflow.order.infrastructure.pricing;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PricingGateway {

    private final PricingClient client;

    public PricingGateway(PricingClient client) {
        this.client = client;
    }

    public QuoteResponse quote(QuoteRequest request) {
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
