package com.recoverpro.server.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.recoverpro.server.config.cache.TwoTierCacheManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.jackson2.SecurityJackson2Modules;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    private GenericJackson2JsonRedisSerializer securityAwareSerializer(ObjectMapper base) {
        ObjectMapper om = base.copy();
        om.registerModules(SecurityJackson2Modules.getModules(getClass().getClassLoader()));
        om.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .allowIfSubType("com.recoverpro.server.")
                        .allowIfSubType("org.springframework.security.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.lang.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return new GenericJackson2JsonRedisSerializer(om);
    }

    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory cf, ObjectMapper objectMapper) {
        GenericJackson2JsonRedisSerializer valueSerializer = securityAwareSerializer(objectMapper);
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer));

        return RedisCacheManager.builder(cf)
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration("userDetails",  base.entryTtl(Duration.ofMinutes(5)))
                .withCacheConfiguration("systemPrompts", base.entryTtl(Duration.ofMinutes(30)))
                .withCacheConfiguration("lucienContext", base.entryTtl(Duration.ofHours(2)))
                .build();
    }

    /**
     * TASK 15.3: {@code recordStats()} was already on (SYSTEM 15 2026-08-18 session) but nothing
     * exported those stats -- Spring Boot's own cache-metrics auto-binder only recognizes a cache
     * that IS a {@code CaffeineCache}, and {@link TwoTierCacheManager} hands out {@code TwoTierCache}
     * wrappers instead, so it silently bound nothing. Registering {@link CaffeineCacheMetrics}
     * directly against each L1's native Caffeine {@code Cache} (unwrapped here, before the
     * TwoTierCache wrapper is built) sidesteps that mismatch and gets cache_gets/cache_puts/
     * cache_evictions onto {@code /actuator/prometheus} tagged by cache name.
     *
     * <p>Dropped the "featureFlags" and "default" L1/L2 buckets this session: neither has ever had
     * a caller -- feature-flag resolution is cached by {@link com.recoverpro.server.service.FeatureFlagService}
     * directly against Redis (a deliberate SYSTEM 20 TASK 20.3 design, not this cache), and no
     * {@code @Cacheable} anywhere names "default". Keeping them would have shipped two permanently
     * empty metric series that look like caches nothing ever uses -- misleading on a dashboard.
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisCacheManager redisCacheManager, MeterRegistry meterRegistry) {
        List<CaffeineCache> l1Caches = List.of(
                caffeineCache("userDetails",   30, TimeUnit.SECONDS, 5_000),
                caffeineCache("systemPrompts", 30, TimeUnit.SECONDS, 500),
                caffeineCache("lucienContext", 30, TimeUnit.SECONDS, 2_000)
        );
        for (CaffeineCache c : l1Caches) {
            CaffeineCacheMetrics.monitor(meterRegistry,
                    (com.github.benmanes.caffeine.cache.Cache<?, ?>) c.getNativeCache(), c.getName());
        }
        return new TwoTierCacheManager(l1Caches, redisCacheManager);
    }

    private static CaffeineCache caffeineCache(String name, long ttl, TimeUnit unit, int maxSize) {
        return new CaffeineCache(name,
                Caffeine.newBuilder()
                        .expireAfterWrite(ttl, unit)
                        .maximumSize(maxSize)
                        .recordStats()
                        .build());
    }
}
