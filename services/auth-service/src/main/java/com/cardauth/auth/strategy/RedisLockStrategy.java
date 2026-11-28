package com.cardauth.auth.strategy;

import com.cardauth.domain.RedisKeys;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/** Redisson distributed lock around the (otherwise racy) read-then-write: safe across service instances. */
@Component
public class RedisLockStrategy implements AuthorizationStrategy {

    private final RedissonClient redisson;
    private final NaiveStrategy inner;

    public RedisLockStrategy(RedissonClient redisson, NaiveStrategy inner) { this.redisson = redisson; this.inner = inner; }

    @Override public String name() { return "redis-lock"; }

    @Override
    public HoldResult hold(String cardId, String authId, long amount) {
        RLock lock = redisson.getLock(RedisKeys.lock(cardId));
        try {
            if (!lock.tryLock(30, 5, TimeUnit.SECONDS)) return HoldResult.declined(0, "LOCK_TIMEOUT");
            try {
                return inner.hold(cardId, authId, amount);
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HoldResult.declined(0, "INTERRUPTED");
        }
    }

    @Override
    public void release(String cardId, String authId, long amount) { inner.release(cardId, authId, amount); }
}
