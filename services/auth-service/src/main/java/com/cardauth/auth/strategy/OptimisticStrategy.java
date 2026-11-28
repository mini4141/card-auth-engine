package com.cardauth.auth.strategy;

import com.cardauth.auth.card.CardBalance;
import com.cardauth.auth.card.CardBalanceRepository;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** JPA @Version check-and-set. A losing writer re-reads and retries with jittered back-off. */
@Component
public class OptimisticStrategy implements AuthorizationStrategy {
    static final int MAX_ATTEMPTS = 200;

    private final CardBalanceRepository balances;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public OptimisticStrategy(CardBalanceRepository balances, TransactionTemplate tx, JdbcTemplate jdbc) {
        this.balances = balances; this.tx = tx; this.jdbc = jdbc;
    }

    @Override public String name() { return "db-optimistic"; }

    @Override
    public HoldResult hold(String cardId, String authId, long amount) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    CardBalance b = balances.findById(cardId).orElseThrow();
                    if (b.getAvailable() < amount) return HoldResult.declined(b.getAvailable(), "INSUFFICIENT_CREDIT");
                    b.setAvailable(b.getAvailable() - amount);
                    return HoldResult.approved(b.getAvailable());
                });
            } catch (OptimisticLockingFailureException e) {
                LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(100_000, 2_000_000L * Math.min(attempt, 10)));
            }
        }
        return HoldResult.declined(0, "RETRY_EXHAUSTED");
    }

    @Override
    public void release(String cardId, String authId, long amount) {
        jdbc.update("update card_balances set available = available + ?, version = version + 1 where card_id = ?", amount, cardId);
    }
}
