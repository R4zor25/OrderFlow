package com.orderflow.catalog_pricing_service.price.config;

import java.time.Duration;
import java.util.Set;

import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import com.orderflow.catalog_pricing_service.price.infrastructure.CachedPriceTiers;
import com.orderflow.catalog_pricing_service.price.infrastructure.PriceTierCache;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class PriceCacheConfiguration {

    @Bean
    CacheManager priceCacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        var serializer = new JacksonJsonRedisSerializer<>(objectMapper, CachedPriceTiers.class);
        var defaults = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
        // Statistics feed the cache.gets metric; Boot only binds caches that exist at startup.
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaults)
            .enableStatistics()
            .initialCacheNames(Set.of(PriceTierCache.CACHE_NAME))
            .build();
    }
}
