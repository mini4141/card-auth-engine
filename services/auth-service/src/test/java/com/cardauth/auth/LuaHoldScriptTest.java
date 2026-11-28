package com.cardauth.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cardauth.domain.LuaScripts;
import com.cardauth.domain.RedisKeys;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;

/** Hold / capture / release semantics of the Lua scripts, against a real Redis. */
class LuaHoldScriptTest {

    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4.2").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void start() {
        redisContainer.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stop() { factory.destroy(); redisContainer.stop(); }

    private long run(String script, List<String> keys, String... args) {
        return redis.execute(new DefaultRedisScript<>(script, Long.class), keys, (Object[]) args);
    }

    @Test
    void holdCaptureAndRelease() {
        String card = "c1";
        List<String> keys = List.of(RedisKeys.available(card), RedisKeys.holds(card));
        redis.opsForValue().set(keys.get(0), "5000");

        assertEquals(3000, run(LuaScripts.HOLD, keys, "a1", "2000"));   // approved, 3000 left
        assertEquals(3000, run(LuaScripts.HOLD, keys, "a1", "2000"));   // same authId again: no second hold
        assertEquals(-1, run(LuaScripts.HOLD, keys, "a2", "4000"));     // over the available credit: declined
        assertEquals("3000", redis.opsForValue().get(keys.get(0)));

        assertEquals(2000, run(LuaScripts.CAPTURE, List.of(keys.get(1)), "a1")); // settlement captures the hold
        assertEquals(0, run(LuaScripts.RELEASE, keys, "a1"));                    // nothing left to release
        assertEquals("3000", redis.opsForValue().get(keys.get(0)));

        assertEquals(2000, run(LuaScripts.HOLD, keys, "a3", "1000"));   // new hold
        assertEquals(1000, run(LuaScripts.RELEASE, keys, "a3"));                 // reverse puts the money back
        assertEquals("3000", redis.opsForValue().get(keys.get(0)));
    }
}
