package com.qb.analytics.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic tests for Redis cache simulation: putIfAbsent, get, TTL.
 */
class RedisCacheRepositoryTest {

    private final RedisCacheRepository redis = new RedisCacheRepository();

    @Test
    void putIfAbsent_firstCall_returnsTrueAndStoresValue() {
        boolean set = redis.putIfAbsent("key1", "v1", 60);
        assertThat(set).isTrue();
        assertThat(redis.get("key1")).isEqualTo("v1");
    }

    @Test
    void putIfAbsent_secondCallSameKey_returnsFalseAndKeepsOriginal() {
        redis.putIfAbsent("key2", "first", 60);
        boolean set = redis.putIfAbsent("key2", "second", 60);
        assertThat(set).isFalse();
        assertThat(redis.get("key2")).isEqualTo("first");
    }

    @Test
    void get_unknownKey_returnsNull() {
        assertThat(redis.get("unknown")).isNull();
    }

    @Test
    void put_thenGet_returnsValue() {
        redis.put("k", "val", 60);
        assertThat(redis.get("k")).isEqualTo("val");
    }
}
