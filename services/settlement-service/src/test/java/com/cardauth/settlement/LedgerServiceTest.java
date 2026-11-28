package com.cardauth.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cardauth.domain.AuthorizationEvent;
import com.cardauth.domain.RedisKeys;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Ledger posting: idempotent on auth_id, and it captures the Redis hold. */
class LedgerServiceTest {

    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17.6");
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4.2").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static JdbcTemplate jdbc;
    static LedgerService ledger;

    @BeforeAll
    static void start() {
        pg.start();
        redisContainer.start();
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/settlement").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        ledger = new LedgerService(jdbc, redis);
    }

    @AfterAll
    static void stop() { factory.destroy(); redisContainer.stop(); pg.stop(); }

    @Test
    void postsOnceAndCapturesTheHold() {
        redis.opsForHash().put(RedisKeys.holds("card-1"), "auth-1", "2500");
        AuthorizationEvent e = new AuthorizationEvent("auth-1", "card-1", 2500, "Cafe", "5814", "APPROVED", "OK", "redis-lua", 0, Instant.now());

        assertTrue(ledger.post(e));
        assertFalse(ledger.post(e)); // duplicate delivery: unique(auth_id) makes it a no-op

        assertEquals(2500L, jdbc.queryForObject("select sum(amount) from ledger_postings where card_id = 'card-1'", Long.class));
        assertEquals(1L, jdbc.queryForObject("select count(*) from ledger_postings where auth_id = 'auth-1'", Long.class));
        assertEquals(0L, redis.opsForHash().size(RedisKeys.holds("card-1")));
    }
}
