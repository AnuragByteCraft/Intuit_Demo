package com.qb.analytics.repository;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class RedisCacheRepository {

    private static class Entry {
        String value;
        long expiresAtEpochMs;
        Entry(String value, long expiresAtEpochMs) {
            this.value = value;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public String get(String key) {
        Entry e = cache.get(key);
        if (e == null) return null;
        if (Instant.now().toEpochMilli() > e.expiresAtEpochMs) {
            cache.remove(key);
            return null;
        }
        return e.value;
    }

    public void put(String key, String value, long ttlSeconds) {
        long exp = Instant.now().toEpochMilli() + (ttlSeconds * 1000);
        cache.put(key, new Entry(value, exp));
    }

    public boolean putIfAbsent(String key, String value, long ttlSeconds) {
        long now = Instant.now().toEpochMilli();
        long exp = now + (ttlSeconds * 1000);
        Entry newEntry = new Entry(value, exp);
        final boolean[] set = { false };
        cache.compute(key, (k, old) -> {
            if (old == null || now > old.expiresAtEpochMs) {
                set[0] = true;
                return newEntry;
            }
            return old;
        });
        return set[0];
    }

    public void delete(String key) {
        cache.remove(key);
    }
}
