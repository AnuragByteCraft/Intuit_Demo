import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TokenBucketRateLimiter {

    private final long capacity;
    private final double refillPerSec;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(long capacity, double refillPerSec) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (refillPerSec <= 0) throw new IllegalArgumentException("refillPerSec must be > 0");
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
    }

    public boolean allow(String key) {
        return allow(key, 1);
    }

    public boolean allow(String key, long tokens) {
        if (tokens <= 0) tokens = 1;
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, refillPerSec));
        return bucket.take(tokens);
    }

    public double retryAfterSeconds(String key) {
        Bucket bucket = buckets.get(key);
        return bucket == null ? 0 : bucket.retryAfterSeconds(1);
    }

    private static final class Bucket {

        private final long cap;
        private final double rate;
        private double tokens;
        private long lastNanos;

        Bucket(long cap, double rate) {
            this.cap = cap;
            this.rate = rate;
            this.tokens = cap;
            this.lastNanos = System.nanoTime();
        }

        synchronized boolean take(long n) {
            refill();
            if (tokens < n) return false;
            tokens -= n;
            return true;
        }

        synchronized double retryAfterSeconds(long n) {
            refill();
            double needed = n - tokens;
            if (needed <= 0) return 0;
            return needed / rate;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastNanos;
            if (elapsed <= 0) return;
            double add = (elapsed / 1_000_000_000.0) * rate;
            if (add <= 0) return;
            tokens = Math.min(cap, tokens + add);
            lastNanos = now;
        }
    }
}