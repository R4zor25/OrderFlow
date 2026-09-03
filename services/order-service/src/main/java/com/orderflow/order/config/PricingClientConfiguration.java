package com.orderflow.order.config;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.orderflow.order.infrastructure.pricing.PricingClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PricingClientProperties.class)
public class PricingClientConfiguration {

    @Bean
    public RestClient pricingRestClient(RestClient.Builder builder, PricingClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.baseUrl().toString())
            .requestFactory(requestFactory)
            .build();
    }

    @Bean
    public PricingClient pricingClient(@Qualifier("pricingRestClient") RestClient restClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
            .build()
            .createClient(PricingClient.class);
    }
}
