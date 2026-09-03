package com.orderflow.catalog_pricing_service.price.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import com.orderflow.catalog_pricing_service.price.infrastructure.CachedPriceTiers;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class PriceCacheConfiguration {

    @Bean
    CacheManager priceCacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        var serializer = new JacksonJsonRedisSerializer<>(objectMapper, CachedPriceTiers.class);
        var defaults = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaults)
            .transactionAware()
            .build();
    }
}
