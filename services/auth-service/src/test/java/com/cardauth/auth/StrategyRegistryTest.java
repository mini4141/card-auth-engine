package com.cardauth.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cardauth.auth.strategy.AuthorizationStrategy;
import com.cardauth.auth.strategy.StrategyRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyRegistryTest {

    private static AuthorizationStrategy stub(String name) {
        return new AuthorizationStrategy() {
            public String name() { return name; }
            public HoldResult hold(String cardId, String authId, long amount) { return HoldResult.approved(0); }
            public void release(String cardId, String authId, long amount) {}
        };
    }

    private final StrategyRegistry registry =
            new StrategyRegistry(List.of(stub("naive"), stub("db-pessimistic"), stub("redis-lua")), "redis-lua");

    @Test
    void headerSelectsStrategyAndConfigIsTheDefault() {
        assertEquals("db-pessimistic", registry.select("db-pessimistic").name());
        assertEquals("redis-lua", registry.select(null).name());
        assertEquals("redis-lua", registry.select("  ").name());
        assertThrows(IllegalArgumentException.class, () -> registry.select("nope"));
    }
}
