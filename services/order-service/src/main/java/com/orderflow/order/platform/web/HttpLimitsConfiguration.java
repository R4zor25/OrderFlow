package com.orderflow.order.platform.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderHttpProperties.class)
public class HttpLimitsConfiguration {
}
