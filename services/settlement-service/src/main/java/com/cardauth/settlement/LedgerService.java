package com.cardauth.settlement;

import com.cardauth.domain.AuthorizationEvent;
import com.cardauth.domain.LuaScripts;
import com.cardauth.domain.RedisKeys;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Posts approved authorisations to the ledger (idempotent on auth_id) and captures the Redis hold. */
@Service
public class LedgerService {
    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> capture = new DefaultRedisScript<>(LuaScripts.CAPTURE, Long.class);

    public LedgerService(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc = jdbc; this.redis = redis; }

    /** @return true if a new posting was written, false for a duplicate delivery or an already reversed hold. */
    @Transactional
    public boolean post(AuthorizationEvent e) {
        int inserted = jdbc.update(
                "insert into ledger_postings (auth_id, card_id, amount, strategy) values (?,?,?,?) on conflict (auth_id) do nothing",
                e.authId(), e.cardId(), e.amount(), e.strategy());
        boolean redisStrategy = "redis-lua".equals(e.strategy());
        if (redisStrategy) {
            // always attempt capture so a redelivery after a crash still converges
            Long captured = redis.execute(capture, List.of(RedisKeys.holds(e.cardId())), e.authId());
            if (inserted == 1 && (captured == null || captured == 0)) {
                // hold already released (reversed) before settlement: nothing to post
                throw new HoldGone(e.authId());
            }
        }
        if (inserted == 0) log.info("duplicate delivery ignored auth={}", e.authId());
        else log.info("posted ledger auth={} card={} amount={}", e.authId(), e.cardId(), e.amount());
        return inserted == 1;
    }

    public static class HoldGone extends RuntimeException {
        public HoldGone(String authId) { super("hold gone for " + authId); }
    }
}
