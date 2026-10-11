package com.orderflow.catalog_pricing_service.price.infrastructure;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.orderflow.catalog_pricing_service.price.domain.PriceTier;

@Component
public class VersionedPriceTierWriter {

    // Compare and set in one Redis step: a list written after commit, or loaded by a slow quote, never replaces a
    // newer one, whatever order the writers finish in.
    private static final DefaultRedisScript<Long> WRITE_IF_NEWER = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[1])
        if current then
          local stored = cjson.decode(current)['version']
          if stored and tonumber(stored) >= tonumber(ARGV[2]) then
            return 0
          end
        end
        redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public VersionedPriceTierWriter(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public boolean write(String group, String sku, long version, List<PriceTier> tiers) {
        // Same key and JSON as the RedisCacheManager, so Cache.get reads what this writes.
        String key = PriceTierCache.CACHE_NAME + "::" + PriceTierCache.key(group, sku);
        String value = json.writeValueAsString(new CachedPriceTiers(version, tiers));
        Long written = redis.execute(WRITE_IF_NEWER, List.of(key), value, Long.toString(version),
            Long.toString(PriceTierCache.TIME_TO_LIVE.toMillis()));
        return written != null && written == 1L;
    }
}
