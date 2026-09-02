package com.orderflow.catalog_pricing_service.product.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProductTimeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
