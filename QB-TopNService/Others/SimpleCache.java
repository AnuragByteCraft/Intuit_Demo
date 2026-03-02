import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class SimpleCache<K, V> {

    private final int maxSize;
    private final long ttlMillis;

    private static class CacheEntry<V> {
        V value;
        long expiryTime;

        CacheEntry(V value, long expiryTime) {
            this.value = value;
            this.expiryTime = expiryTime;
        }
    }

    private final Map<K, CacheEntry<V>> cache;

    public SimpleCache(int maxSize, long ttlMillis) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                return size() > SimpleCache.this.maxSize;
            }
        };
    }

    public synchronized void put(K key, V value) {
        long expiry = Instant.now().toEpochMilli() + ttlMillis;
        cache.put(key, new CacheEntry<>(value, expiry));
    }

    public synchronized V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry == null) return null;
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
