package com.orderflow.order.api;

import java.util.UUID;

import com.orderflow.order.application.OrderAccess;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class OrderAccessResolver {

    public OrderAccess access(JwtAuthenticationToken authentication) {
        if (hasAuthority(authentication, "ROLE_ADMIN")) {
            return OrderAccess.admin();
        }
        return OrderAccess.customer(customerId(authentication));
    }

    public UUID customerId(JwtAuthenticationToken authentication) {
        String claim = authentication.getToken().getClaimAsString("customer_id");
        if (claim == null) {
            throw new AccessDeniedException("Token has no customer_id claim");
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("Token has an invalid customer_id claim");
        }
    }

    private static boolean hasAuthority(JwtAuthenticationToken authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
