package com.recoverpro.server.config.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

@Slf4j
public class TwoTierCache implements Cache {

    private final String name;
    private final CaffeineCache l1;
    @Nullable private final Cache l2;

    public TwoTierCache(String name, CaffeineCache l1, @Nullable Cache l2) {
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override public String getName()        { return name; }
    @Override public Object getNativeCache() { return l1.getNativeCache(); }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        ValueWrapper v = l1.get(key);
        if (v != null) return v;
        v = l2Read(key, () -> l2 != null ? l2.get(key) : null);
        if (v != null) l1.put(key, v.get());
        return v;
    }

    @Override
    @Nullable
    public <T> T get(Object key, @Nullable Class<T> type) {
        T v = l1.get(key, type);
        if (v != null) return v;
        T l2val = l2Read(key, () -> l2 != null ? l2.get(key, type) : null);
        if (l2val != null) l1.put(key, l2val);
        return l2val;
    }

    /**
     * TASK 15.3: routed through the native Caffeine cache's atomic get-or-compute rather than the
     * separate l1.get()/valueLoader.call()/put() steps above, so N callers racing on the same cold
     * key (only reachable when {@code @Cacheable(sync = true)}) block on one in-flight computation
     * instead of each independently missing L1, missing L2, and invoking valueLoader.
     */
    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeL1 =
                (com.github.benmanes.caffeine.cache.Cache<Object, Object>) l1.getNativeCache();
        return (T) nativeL1.get(key, k -> {
            ValueWrapper l2hit = l2Read(k, () -> l2 != null ? l2.get(k) : null);
            if (l2hit != null) return l2hit.get();
            Object loaded;
            try {
                loaded = valueLoader.call();
            } catch (Exception e) {
                throw new ValueRetrievalException(k, valueLoader, e);
            }
            if (loaded != null && l2 != null) l2.put(k, loaded);
            return loaded;
        });
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        l1.put(key, value);
        if (l2 != null) l2.put(key, value);
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        ValueWrapper existing = get(key);
        if (existing != null) return existing;
        put(key, value);
        return null;
    }

    @Override
    public void evict(Object key) {
        l1.evict(key);
        if (l2 != null) l2.evict(key);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        boolean hit = l1.evictIfPresent(key);
        if (l2 != null) hit |= l2.evictIfPresent(key);
        return hit;
    }

    @Override
    public void clear() {
        l1.clear();
        if (l2 != null) l2.clear();
    }

    @Override
    public boolean invalidate() {
        l1.invalidate();
        if (l2 != null) l2.invalidate();
        return true;
    }

    @Nullable
    private <T> T l2Read(Object key, Callable<T> read) {
        try {
            return read.call();
        } catch (Exception e) {
            log.warn("L2 cache read failed for key '{}' in '{}' — treating as miss: {}", key, name, e.getMessage());
            return null;
        }
    }
}
