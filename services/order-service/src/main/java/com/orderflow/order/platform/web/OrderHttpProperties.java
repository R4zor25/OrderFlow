package com.orderflow.order.platform.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "order.http")
public record OrderHttpProperties(DataSize maxRequestBodySize) {

    public OrderHttpProperties {
        if (maxRequestBodySize == null || maxRequestBodySize.toBytes() <= 0) {
            throw new IllegalArgumentException("order.http.max-request-body-size must be positive");
        }
    }
}
