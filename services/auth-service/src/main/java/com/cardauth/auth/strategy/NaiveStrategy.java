package com.cardauth.auth.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-then-write with no locking and no transaction. This is deliberately wrong:
 * two requests can read the same balance and both write balance - amount (lost update).
 */
@Component
public class NaiveStrategy implements AuthorizationStrategy {
    private static final Logger log = LoggerFactory.getLogger(NaiveStrategy.class);

    private final JdbcTemplate jdbc;

    public NaiveStrategy(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public String name() { return "naive"; }

    @Override
    public HoldResult hold(String cardId, String authId, long amount) {
        Long available = jdbc.queryForObject("select available from card_balances where card_id = ?", Long.class, cardId);
        log.debug("naive READ card={} auth={} available={}", cardId, authId, available);
        if (available == null || available < amount) return HoldResult.declined(available == null ? 0 : available, "INSUFFICIENT_CREDIT");
        long after = available - amount;
        jdbc.update("update card_balances set available = ? where card_id = ?", after, cardId);
        log.debug("naive WRITE card={} auth={} wrote={}", cardId, authId, after);
        return HoldResult.approved(after);
    }

    @Override
    public void release(String cardId, String authId, long amount) {
        jdbc.update("update card_balances set available = available + ? where card_id = ?", amount, cardId);
    }
}
