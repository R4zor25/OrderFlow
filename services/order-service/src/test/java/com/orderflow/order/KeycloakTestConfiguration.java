package com.orderflow.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
public class KeycloakTestConfiguration {

    @Bean
    DynamicPropertyRegistrar keycloakProperties() {
        return registry -> {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakTestContainer::realmUrl);
            registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> KeycloakTestContainer.realmUrl() + "/protocol/openid-connect/certs");
        };
    }
}
