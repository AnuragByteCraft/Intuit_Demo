import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory cache with both TTL and LRU eviction.
 * TTL: each entry expires after ttlMillis from put(); we only check/remove expired entries on get() (lazy).
 * LRU: when the cache already has maxSize entries, put() triggers removeEldestEntry and the least recently used entry is evicted (size-based).
 * So: put() stores expiry (for TTL) and may cause LRU eviction; get() enforces TTL. Two independent mechanisms.
 */
public class SimpleCache<K, V> {

    private final int maxSize;
    private final long ttlMillis;

    /** Wraps a value with its expiry time so we can enforce TTL on get(). */
    private static class CacheEntry<V> {
        V value;
        long expiryTime;

        CacheEntry(V value, long expiryTime) {
            this.value = value;
            this.expiryTime = expiryTime;
        }
    }

    /** Access-ordered LinkedHashMap: iteration order = least recently accessed first, so eldest = LRU. */
    private final Map<K, CacheEntry<V>> cache;

    public SimpleCache(int maxSize, long ttlMillis) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
        // true = access order (not insert order): get() moves entry to end; eldest = least recently used
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                return size() > SimpleCache.this.maxSize;
            }
        };
    }

    public synchronized void put(K key, V value) {
        // Store expiry so get() can enforce TTL (time-based). Eviction here is LRU only (removeEldestEntry when over maxSize).
        long expiry = Instant.now().toEpochMilli() + ttlMillis;
        cache.put(key, new CacheEntry<>(value, expiry));
    }

    public synchronized V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry == null) return null;
        // TTL check: if expired, remove and return null (lazy expiry)
        if (Instant.now().toEpochMilli() > entry.expiryTime) {
            cache.remove(key);
            return null;
        }
        return entry.value;
    }

    public synchronized int size() {
        return cache.size();
    }
}
/*

Okay see… this is just a lightweight in-memory cache where we’re solving two things — data freshness and memory control.
Whenever we put something into cache, we store the value along with an expiry timestamp based on TTL. Then during every read, we simply check if that entry has expired. If it has, we remove it and return null. So expiry happens lazily without running any background cleanup thread.
For memory control, we’re using a LinkedHashMap in access-order mode. That automatically keeps track of recently used entries, and once cache size crosses the limit, the least recently used item gets evicted automatically.
All methods are synchronized so multiple threads can safely access the cache without corrupting state.
So basically — TTL removes stale data, LRU controls memory growth, and together we get a simple thread-safe local cache without needing Redis or any heavy framework.

*/