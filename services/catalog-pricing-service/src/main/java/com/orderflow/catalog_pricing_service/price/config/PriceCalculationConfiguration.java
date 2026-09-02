package com.orderflow.catalog_pricing_service.price.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.orderflow.catalog_pricing_service.price.domain.PriceCalculator;

@Configuration(proxyBeanMethods = false)
public class PriceCalculationConfiguration {

    @Bean
    PriceCalculator priceCalculator(Clock clock) {
        return new PriceCalculator(clock);
    }
}
