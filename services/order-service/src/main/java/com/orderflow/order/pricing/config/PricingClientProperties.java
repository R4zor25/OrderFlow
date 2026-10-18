package com.orderflow.order.pricing.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order.pricing")
public record PricingClientProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {

    public PricingClientProperties {
        if (baseUrl == null || !baseUrl.isAbsolute() || baseUrl.getHost() == null
            || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
            || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Pricing base URL and positive timeouts are required");
        }
    }
}
