package com.cardauth.auth.card;

import com.cardauth.domain.Json;
import com.cardauth.domain.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardService {

    public record CardProfile(String id, String holder, long creditLimit) {}
    public record CardView(String id, String holder, long limit, long available, long holds) {}

    private final CardRepository cards;
    private final CardBalanceRepository balances;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;

    public CardService(CardRepository cards, CardBalanceRepository balances, StringRedisTemplate redis, RedissonClient redisson) {
        this.cards = cards; this.balances = balances; this.redis = redis; this.redisson = redisson;
    }

    /** Creates the card and primes Redis available credit from Postgres. */
    @Transactional
    public CardView create(String holder, long limit) {
        Card c = cards.save(new Card(UUID.randomUUID().toString(), holder, limit));
        balances.save(new CardBalance(c.getId(), limit));
        redis.opsForValue().set(RedisKeys.available(c.getId()), Long.toString(limit));
        return toView(c);
    }

    /** Limit changes are serialised with a Redisson lock and applied to Redis as a delta so live holds survive. */
    public CardView changeLimit(String id, long newLimit) {
        RLock lock = redisson.getLock(RedisKeys.lock(id));
        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) throw new IllegalStateException("limit change busy");
            long delta = applyLimit(id, newLimit);
            redis.opsForValue().increment(RedisKeys.available(id), delta);
            redis.delete(RedisKeys.profile(id)); // cache-aside invalidation
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
        return view(id).orElseThrow();
    }

    @Transactional
    public long applyLimit(String id, long newLimit) {
        Card c = cards.findById(id).orElseThrow(() -> new NotFound(id));
        long delta = newLimit - c.getCreditLimit();
        c.setCreditLimit(newLimit);
        CardBalance b = balances.findForUpdate(id).orElseThrow();
        b.setAvailable(b.getAvailable() + delta);
        return delta;
    }

    /** Cache-aside read of the card profile (Redis, 10 min TTL). */
    public Optional<CardProfile> profile(String id) {
        String cached = redis.opsForValue().get(RedisKeys.profile(id));
        if (cached != null) return Optional.of(Json.read(cached, CardProfile.class));
        return cards.findById(id).map(c -> {
            CardProfile p = new CardProfile(c.getId(), c.getHolder(), c.getCreditLimit());
            redis.opsForValue().set(RedisKeys.profile(id), Json.write(p), Duration.ofMinutes(10));
            return p;
        });
    }

    public Optional<CardView> view(String id) {
        return cards.findById(id).map(this::toView);
    }

    public List<CardView> list() {
        return cards.findAll().stream().map(this::toView).toList();
    }

    private CardView toView(Card c) {
        String avail = redis.opsForValue().get(RedisKeys.available(c.getId()));
        Map<Object, Object> holds = redis.opsForHash().entries(RedisKeys.holds(c.getId()));
        long held = holds.values().stream().mapToLong(v -> Long.parseLong(v.toString())).sum();
        return new CardView(c.getId(), c.getHolder(), c.getCreditLimit(), avail == null ? 0 : Long.parseLong(avail), held);
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String id) { super("card not found: " + id); }
    }
}
