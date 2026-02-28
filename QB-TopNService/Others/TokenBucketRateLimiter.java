import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory Token Bucket rate limiter.
 *
 * What it does:
 * - Maintains a separate token bucket per key (ex: tenant:merchant).
 * - Allows bursts up to "capacity" and then refills at "refillPerSec" tokens/sec.
 *
 * Thread-safety:
 * - Buckets map is ConcurrentHashMap (safe under concurrency).
 * - Each individual Bucket uses synchronized methods so "refill + consume" is atomic per key.
 *   (No race where two threads both consume the same tokens.)
 *
 * Notes:
 * - This is per-process (per pod) limiting. In real prod for global limits, you'd back it by Redis.
 */
public final class TokenBucketRateLimiter {

    // Max tokens (burst size)
    private final long capacity;

    // Tokens added per second (steady rate)
    private final double refillPerSec;

    // One bucket per key (tenant/merchant), stored safely under concurrency
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(long capacity, double refillPerSec) {
        // Basic guardrails
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (refillPerSec <= 0) throw new IllegalArgumentException("refillPerSec must be > 0");
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
    }

    // Common case: one request consumes one token
    public boolean allow(String key) {
        return allow(key, 1);
    }

    public boolean allow(String key, long tokens) {
        // Treat non-positive as 1 token to avoid weird caller behavior
        if (tokens <= 0) tokens = 1;

        // Create bucket once per key (thread-safe), then try to consume tokens
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, refillPerSec));
        return bucket.take(tokens);
    }

    /** Seconds until 1 token is available. Use when allow() returns false. */
    public double retryAfterSeconds(String key) {
        Bucket bucket = buckets.get(key);
        return bucket == null ? 0 : bucket.retryAfterSeconds(1);
    }

    /**
     * One bucket instance represents the current rate-limit state for a single key.
     */
    private static final class Bucket {

        private final long cap;      // burst cap
        private final double rate;   // refill rate tokens/sec

        private double tokens;       // current tokens (double supports fractional refill)
        private long lastNanos;      // last time we refilled (monotonic clock)

        Bucket(long cap, double rate) {
            this.cap = cap;
            this.rate = rate;
            this.tokens = cap;                 // start full (allow initial burst)
            this.lastNanos = System.nanoTime();
        }

        /**
         * Try to consume tokens.
         * Synchronized so that "refill + check + consume" is atomic per key.
         */
        synchronized boolean take(long n) {
            refill();                          // top-up tokens based on elapsed time
            if (tokens < n) return false;      // not enough tokens -> rate limited
            tokens -= n;                       // consume
            return true;
        }

        /**
         * Seconds until at least n tokens are available (does not consume).
         * Caller should invoke when allow() returns false.
         */
        synchronized double retryAfterSeconds(long n) {
            refill();
            double needed = n - tokens;
            if (needed <= 0) return 0;
            return needed / rate;
        }

        /**
         * Refill tokens based on time passed since last refill.
         */
        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastNanos;
            if (elapsed <= 0) return;          // no time passed (or clock anomaly)

            // Convert elapsed time to seconds and add tokens = seconds * rate
            double add = (elapsed / 1_000_000_000.0) * rate;
            if (add <= 0) return;

            // Cap tokens at burst capacity
            tokens = Math.min(cap, tokens + add);
            lastNanos = now;                   // move refill cursor
        }
    }
}

/*
Okay see… rate limiting is basically controlling how fast a tenant or merchant can hit our system.
So what we do is we give every tenant a small token bucket.
Every request consumes one token.
Tokens keep getting refilled slowly over time.

If tokens are available, request goes through.
If bucket becomes empty, we temporarily reject requests.

This allows short bursts — like flash sales — but still protects backend from sustained overload.

Internally we keep one bucket per tenant or merchant using a concurrent map, and each bucket refills itself based on elapsed time instead of running background threads.

Also the configuration like capacity and refill rate is kept final so rate limiting behavior stays stable and thread-safe once system starts.
*/