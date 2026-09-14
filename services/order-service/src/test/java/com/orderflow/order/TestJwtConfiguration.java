package com.orderflow.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@TestConfiguration(proxyBeanMethods = false)
public class TestJwtConfiguration {

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(TestJwts.publicKey()).build();
    }
}
