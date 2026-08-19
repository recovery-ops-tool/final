package com.recoverpro.server.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SYSTEM-PLAN 15.3: {@code recordStats()} was already on for every L1 Caffeine cache (see
 * {@code docs/SYSTEM-15-CACHING.md}), but nothing exported those stats anywhere -- Spring Boot's
 * built-in cache-metrics auto-binder only recognizes a cache that IS a {@code CaffeineCache}, and
 * {@link com.recoverpro.server.config.cache.TwoTierCacheManager} hands out {@code TwoTierCache}
 * wrappers instead, so it silently bound nothing. This proves {@link RedisCacheConfig#cacheManager}
 * registers Micrometer's Caffeine binder directly against each L1's native cache, and that the two
 * dead buckets ("featureFlags", "default" -- neither ever had a caller) are gone.
 */
class RedisCacheConfigTest {

    @Test
    void cacheManager_registersMicrometerStatsForEveryRealCache() {
        RedisCacheConfig config = new RedisCacheConfig();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisCacheManager redisCacheManager = mock(RedisCacheManager.class);

        CacheManager cacheManager = config.cacheManager(redisCacheManager, meterRegistry);

        assertThat(cacheManager.getCacheNames())
                .containsExactlyInAnyOrder("userDetails", "systemPrompts", "lucienContext");

        for (String name : java.util.List.of("userDetails", "systemPrompts", "lucienContext")) {
            assertThat(meterRegistry.find("cache.gets").tag("cache", name).meter())
                    .as("cache.gets meter for " + name)
                    .isNotNull();
            assertThat(meterRegistry.find("cache.puts").tag("cache", name).meter())
                    .as("cache.puts meter for " + name)
                    .isNotNull();
        }
    }

    @Test
    void cacheManager_dropsTheTwoDeadBuckets() {
        RedisCacheConfig config = new RedisCacheConfig();
        RedisCacheManager redisCacheManager = mock(RedisCacheManager.class);

        CacheManager cacheManager = config.cacheManager(redisCacheManager, new SimpleMeterRegistry());

        // TwoTierCacheManager falls through to the (mocked, empty) RedisCacheManager for any name
        // it doesn't itself own, so this only proves "featureFlags"/"default" aren't in the L1 set
        // -- exactly the thing this task's cleanup removed.
        assertThat(cacheManager.getCacheNames()).doesNotContain("featureFlags", "default");
    }

    @Test
    void caffeineCache_getOrCompute_collapsesConcurrentColdKeyToOneComputation() throws Exception {
        com.github.benmanes.caffeine.cache.Cache<String, String> native1 =
                com.github.benmanes.caffeine.cache.Caffeine.newBuilder().recordStats().build();

        java.util.concurrent.atomic.AtomicInteger computations = new java.util.concurrent.atomic.AtomicInteger();
        int threads = 12;
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);

        java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return native1.get("cold-key", k -> {
                    computations.incrementAndGet();
                    try {
                        Thread.sleep(50); // widen the race window
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "computed-value";
                });
            }));
        }
        ready.await();
        go.countDown();
        for (java.util.concurrent.Future<String> f : futures) {
            assertThat(f.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("computed-value");
        }
        pool.shutdown();

        assertThat(computations.get())
                .as("N callers racing on the same cold key must collapse to one backing computation")
                .isEqualTo(1);
    }
}
