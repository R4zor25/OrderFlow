package com.orderflow.catalog_pricing_service.config;

import java.util.ArrayList;
import java.util.Collection;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.orderflow.catalog_pricing_service.api.SecurityProblemHandler;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(requests -> requests
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/products").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/products/*/prices").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/quotes").hasAuthority("SCOPE_pricing.quote")
                .anyRequest().authenticated())
            .oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                .authenticationEntryPoint(problems)
                .accessDeniedHandler(problems))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(problems)
                .accessDeniedHandler(problems));
        return http.build();
    }

    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        var scopes = new JwtGrantedAuthoritiesConverter();
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            authorities.addAll(roles.convert(jwt));
            return authorities;
        });
        return converter;
    }
}
