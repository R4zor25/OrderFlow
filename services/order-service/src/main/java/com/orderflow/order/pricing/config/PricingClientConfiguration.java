package com.orderflow.order.pricing.config;

import java.net.http.HttpClient;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.RemoveAuthorizedClientOAuth2AuthorizationFailureHandler;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.orderflow.order.pricing.PricingClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PricingClientProperties.class)
public class PricingClientConfiguration {

    public static final String PRICING_REGISTRATION_ID = "catalog-pricing";

    // Pricing calls run on customer request threads; a fixed principal keeps the service token separate from the customer.
    private static final Authentication SERVICE_PRINCIPAL =
        UsernamePasswordAuthenticationToken.authenticated("order-service", "n/a", AuthorityUtils.NO_AUTHORITIES);

    @Bean
    public OAuth2AuthorizedClientManager pricingAuthorizedClientManager(ClientRegistrationRepository registrations,
                                                                        OAuth2AuthorizedClientService clients,
                                                                        PricingClientProperties properties) {
        var tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder()
            .configureMessageConverters(converters -> {
                converters.addCustomConverter(new FormHttpMessageConverter());
                converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
            })
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .requestFactory(requestFactory(properties))
            .build());
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
            .clientCredentials(clientCredentials -> clientCredentials.accessTokenResponseClient(tokenClient))
            .build());
        return manager;
    }

    @Bean
    public RestClient pricingRestClient(RestClient.Builder builder, PricingClientProperties properties,
                                        OAuth2AuthorizedClientManager pricingAuthorizedClientManager,
                                        OAuth2AuthorizedClientService clients) {
        var oauth = new OAuth2ClientHttpRequestInterceptor(pricingAuthorizedClientManager);
        oauth.setClientRegistrationIdResolver(request -> PRICING_REGISTRATION_ID);
        oauth.setPrincipalResolver(request -> SERVICE_PRINCIPAL);
        // A 403 without an error parameter is mapped to insufficient_scope, which the default handler does not evict.
        oauth.setAuthorizationFailureHandler(new RemoveAuthorizedClientOAuth2AuthorizationFailureHandler(
            (registrationId, principal, attributes) -> clients.removeAuthorizedClient(registrationId, principal.getName()),
            Set.of(OAuth2ErrorCodes.INVALID_TOKEN, OAuth2ErrorCodes.INVALID_GRANT, OAuth2ErrorCodes.INSUFFICIENT_SCOPE)));
        return builder.baseUrl(properties.baseUrl().toString())
            .requestFactory(requestFactory(properties))
            .requestInterceptor(oauth)
            .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(PricingClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return requestFactory;
    }

    @Bean
    public PricingClient pricingClient(@Qualifier("pricingRestClient") RestClient restClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
            .build()
            .createClient(PricingClient.class);
    }
}
