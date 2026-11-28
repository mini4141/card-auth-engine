package com.cardauth.auth.authz;

import com.cardauth.auth.card.CardService;
import com.cardauth.auth.store.AuthLogRepository;
import com.cardauth.auth.strategy.AuthorizationStrategy;
import com.cardauth.auth.strategy.AuthorizationStrategy.HoldResult;
import com.cardauth.auth.strategy.StrategyRegistry;
import com.cardauth.domain.AuthorizationEvent;
import com.cardauth.domain.Decision;
import com.cardauth.domain.Json;
import com.cardauth.domain.RedisKeys;
import com.cardauth.domain.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {
    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);
    private static final Duration IDEM_TTL = Duration.ofHours(24);
    private static final String PENDING = "PENDING";

    public record AuthorizationRequest(String cardId, long amount, String merchant, String mcc) {}
    public record AuthorizationResponse(String authId, String decision, String reason, long availableAfter) {}

    private final StrategyRegistry strategies;
    private final CardService cards;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final AuthLogRepository authLog;
    private final MeterRegistry meters;
    // Decision side effects (audit log, event) run off the request path on a small PLATFORM-thread pool:
    // blocking SDK calls pinned inside synchronized blocks would otherwise starve the virtual-thread carriers.
    private final ExecutorService async = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "audit-publisher");
        t.setDaemon(true);
        return t;
    });
    private final Semaphore dynamoPermits = new Semaphore(8);

    public AuthorizationService(StrategyRegistry strategies, CardService cards, StringRedisTemplate redis,
                                KafkaTemplate<String, String> kafka, AuthLogRepository authLog, MeterRegistry meters) {
        this.strategies = strategies; this.cards = cards; this.redis = redis;
        this.kafka = kafka; this.authLog = authLog; this.meters = meters;
    }

    public AuthorizationResponse authorise(String idempotencyKey, String strategyName, AuthorizationRequest req) {
        AuthorizationStrategy strategy = strategies.select(strategyName);
        String idemKey = RedisKeys.idem(idempotencyKey);
        Boolean first = redis.opsForValue().setIfAbsent(idemKey, PENDING, IDEM_TTL);
        if (!Boolean.TRUE.equals(first)) return awaitStoredDecision(idemKey);

        long start = System.nanoTime();
        String authId = UUID.randomUUID().toString();
        try {
            HoldResult r = cards.profile(req.cardId()).isEmpty()
                    ? HoldResult.declined(0, "CARD_NOT_FOUND")
                    : strategy.hold(req.cardId(), authId, req.amount());
            Decision decision = r.approved() ? Decision.APPROVED : Decision.DECLINED;
            AuthorizationResponse resp = new AuthorizationResponse(authId, decision.name(), r.reason(), r.availableAfter());
            AuthorizationEvent event = new AuthorizationEvent(authId, req.cardId(), req.amount(), req.merchant(), req.mcc(),
                    decision.name(), r.reason(), strategy.name(), r.availableAfter(), Instant.now());

            redis.opsForValue().set(idemKey, Json.write(resp), IDEM_TTL);
            if (r.approved()) redis.opsForValue().set(RedisKeys.auth(authId), Json.write(event), Duration.ofDays(7));

            long nanos = System.nanoTime() - start;
            Timer.builder("auth.latency").tag("strategy", strategy.name()).tag("decision", decision.name())
                    .publishPercentileHistogram().register(meters).record(Duration.ofNanos(nanos));
            log.info("authorisation {} card={} amount={} decision={} strategy={}", authId, req.cardId(), req.amount(), decision, strategy.name());
            async.execute(() -> publish(event, nanos / 1_000_000));
            return resp;
        } catch (RuntimeException e) {
            redis.delete(idemKey); // let the client retry the same key
            throw e;
        }
    }

    /** Releases the hold of an approved authorisation. */
    public boolean reverse(String authId) {
        String json = redis.opsForValue().get(RedisKeys.auth(authId));
        if (json == null) return false;
        AuthorizationEvent e = Json.read(json, AuthorizationEvent.class);
        strategies.select(e.strategy()).release(e.cardId(), authId, e.amount());
        redis.delete(RedisKeys.auth(authId));
        log.info("reversed authorisation {} card={} amount={}", authId, e.cardId(), e.amount());
        return true;
    }

    private void publish(AuthorizationEvent event, long latencyMs) {
        try {
            kafka.send(Topics.AUTHORIZATIONS, event.cardId(), Json.write(event)); // key = cardId -> per-card ordering
        } catch (RuntimeException e) {
            log.error("kafka publish failed for {}", event.authId(), e);
        }
        saveAuditLog(event, latencyMs);
    }

    /** Bounded concurrency + retries: a burst must not overwhelm DynamoDB or drop audit items. */
    private void saveAuditLog(AuthorizationEvent event, long latencyMs) {
        dynamoPermits.acquireUninterruptibly();
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    authLog.save(event, latencyMs);
                    return;
                } catch (RuntimeException e) {
                    if (attempt == 5) { log.error("dynamodb log failed for {}: {}", event.authId(), e.toString()); return; }
                    try { Thread.sleep(50L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        } finally {
            dynamoPermits.release();
        }
    }

    private AuthorizationResponse awaitStoredDecision(String idemKey) {
        for (int i = 0; i < 100; i++) {
            String v = redis.opsForValue().get(idemKey);
            if (v != null && !PENDING.equals(v)) return Json.read(v, AuthorizationResponse.class);
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new IllegalStateException("original request still in flight for " + idemKey);
    }
}
