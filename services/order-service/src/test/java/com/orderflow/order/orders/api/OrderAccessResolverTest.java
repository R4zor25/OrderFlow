package com.orderflow.order.orders.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.orderflow.order.orders.application.OrderAccess;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderAccessResolverTest {

    private static final UUID CUSTOMER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final OrderAccessResolver resolver = new OrderAccessResolver();

    @Test
    void customerTokenResolvesToOwnAccess() {
        var access = resolver.access(token(Map.of("customer_id", CUSTOMER.toString()), "ROLE_CUSTOMER"));
        assertThat(access).isEqualTo(OrderAccess.customer(CUSTOMER));
    }

    @Test
    void adminRoleWinsOverCustomerRole() {
        var access = resolver.access(token(Map.of("customer_id", CUSTOMER.toString()), "ROLE_CUSTOMER", "ROLE_ADMIN"));
        assertThat(access).isEqualTo(OrderAccess.admin());
    }

    @Test
    void rejectsCustomerTokenWithoutCustomerId() {
        assertThatThrownBy(() -> resolver.access(token(Map.of(), "ROLE_CUSTOMER")))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsMalformedCustomerId() {
        assertThatThrownBy(() -> resolver.customerId(token(Map.of("customer_id", "not-a-uuid"), "ROLE_CUSTOMER")))
            .isInstanceOf(AccessDeniedException.class);
    }

    private static JwtAuthenticationToken token(Map<String, Object> claims, String... authorities) {
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
            .claims(values -> values.putAll(claims)).build();
        return new JwtAuthenticationToken(jwt, List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }
}
