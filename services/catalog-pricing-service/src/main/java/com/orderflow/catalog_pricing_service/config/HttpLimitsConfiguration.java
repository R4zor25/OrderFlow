package com.orderflow.catalog_pricing_service.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogHttpProperties.class)
public class HttpLimitsConfiguration {
}
