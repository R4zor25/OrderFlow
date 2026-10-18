package com.orderflow.catalog_pricing_service.platform.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "catalog.http")
public record CatalogHttpProperties(DataSize maxRequestBodySize) {

    public CatalogHttpProperties {
        if (maxRequestBodySize == null || maxRequestBodySize.toBytes() <= 0) {
            throw new IllegalArgumentException("catalog.http.max-request-body-size must be positive");
        }
    }
}
