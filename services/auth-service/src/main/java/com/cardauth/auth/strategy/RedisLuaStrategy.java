package com.cardauth.auth.strategy;

import com.cardauth.domain.LuaScripts;
import com.cardauth.domain.RedisKeys;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Single atomic Lua script: if available >= amount then available -= amount and record the hold. */
@Component
public class RedisLuaStrategy implements AuthorizationStrategy {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> hold = new DefaultRedisScript<>(LuaScripts.HOLD, Long.class);
    private final DefaultRedisScript<Long> release = new DefaultRedisScript<>(LuaScripts.RELEASE, Long.class);

    public RedisLuaStrategy(StringRedisTemplate redis) { this.redis = redis; }

    @Override public String name() { return "redis-lua"; }

    @Override
    public HoldResult hold(String cardId, String authId, long amount) {
        Long r = redis.execute(hold, List.of(RedisKeys.available(cardId), RedisKeys.holds(cardId)), authId, Long.toString(amount));
        if (r == null || r == -2) return HoldResult.declined(0, "CARD_NOT_PRIMED");
        if (r == -1) {
            String a = redis.opsForValue().get(RedisKeys.available(cardId));
            return HoldResult.declined(a == null ? 0 : Long.parseLong(a), "INSUFFICIENT_CREDIT");
        }
        return HoldResult.approved(r);
    }

    @Override
    public void release(String cardId, String authId, long amount) {
        redis.execute(release, List.of(RedisKeys.available(cardId), RedisKeys.holds(cardId)), authId);
    }
}
