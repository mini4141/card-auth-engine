package com.cardauth.auth.reconcile;

import com.cardauth.domain.RedisKeys;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Compares, per card, what each store says is used credit with what the ledger and open holds explain.
 * redisDrift = (limit - redisAvailable) - (open holds + ledger postings made by redis-lua)
 * dbDrift    = (limit - dbAvailable) - ledger postings made by the db-* / naive / redis-lock strategies
 */
@Service
public class ReconcileService {
    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    public record CardDrift(String cardId, long limit, long redisAvailable, long redisHolds, long redisLedger,
                            long redisDrift, long dbAvailable, long dbLedger, long dbDrift, long approvedTotal) {}
    public record Report(List<CardDrift> cards, long totalAbsDrift, long overspentCards) {}

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public ReconcileService(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc = jdbc; this.redis = redis; }

    public Report reconcile() {
        boolean ledger = Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('ledger_postings') is not null", Boolean.class));
        var rows = jdbc.queryForList("select c.id, c.credit_limit, b.available from cards c join card_balances b on b.card_id = c.id");
        List<CardDrift> out = rows.stream().map(r -> drift(r, ledger)).toList();
        long total = out.stream().mapToLong(d -> Math.abs(d.redisDrift()) + Math.abs(d.dbDrift())).sum();
        long over = out.stream().filter(d -> d.approvedTotal() > d.limit()).count();
        return new Report(out, total, over);
    }

    private CardDrift drift(Map<String, Object> r, boolean ledger) {
        String id = (String) r.get("id");
        long limit = ((Number) r.get("credit_limit")).longValue();
        long dbAvail = ((Number) r.get("available")).longValue();
        String a = redis.opsForValue().get(RedisKeys.available(id));
        long redisAvail = a == null ? limit : Long.parseLong(a);
        long holds = redis.opsForHash().entries(RedisKeys.holds(id)).values().stream().mapToLong(v -> Long.parseLong(v.toString())).sum();
        long ledgerRedis = 0, ledgerDb = 0;
        if (ledger) {
            ledgerRedis = sum("select coalesce(sum(amount),0) from ledger_postings where card_id = ? and strategy = 'redis-lua'", id);
            ledgerDb = sum("select coalesce(sum(amount),0) from ledger_postings where card_id = ? and strategy <> 'redis-lua'", id);
        }
        long redisDrift = (limit - redisAvail) - (holds + ledgerRedis);
        long dbDrift = ledgerDb - (limit - dbAvail); // >0: ledger holds more than the balance shows (lost updates)
        return new CardDrift(id, limit, redisAvail, holds, ledgerRedis, redisDrift, dbAvail, ledgerDb, dbDrift, ledgerRedis + holds + ledgerDb);
    }

    private long sum(String sql, String cardId) { return jdbc.queryForObject(sql, Long.class, cardId); }

    @Scheduled(fixedDelayString = "${app.reconcile.interval-ms:60000}", initialDelayString = "${app.reconcile.interval-ms:60000}")
    void scheduled() {
        Report r = reconcile();
        log.info("reconciliation cards={} totalAbsDrift={} overspentCards={}", r.cards().size(), r.totalAbsDrift(), r.overspentCards());
    }
}
