package com.orderflow.order.pricing;

import com.orderflow.order.pricing.quote.QuoteRequest;
import com.orderflow.order.pricing.quote.QuoteResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange
public interface PricingClient {

    @PostExchange("/api/v1/quotes")
    QuoteResponse quote(@RequestBody QuoteRequest request);
}
