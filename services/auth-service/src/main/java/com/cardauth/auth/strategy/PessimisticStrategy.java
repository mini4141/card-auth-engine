package com.cardauth.auth.strategy;

import com.cardauth.auth.card.CardBalance;
import com.cardauth.auth.card.CardBalanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** SELECT ... FOR UPDATE inside a transaction: concurrent requests on one card queue on the row lock. */
@Component
public class PessimisticStrategy implements AuthorizationStrategy {

    private final CardBalanceRepository balances;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public PessimisticStrategy(CardBalanceRepository balances, TransactionTemplate tx, JdbcTemplate jdbc) {
        this.balances = balances; this.tx = tx; this.jdbc = jdbc;
    }

    @Override public String name() { return "db-pessimistic"; }

    @Override
    public HoldResult hold(String cardId, String authId, long amount) {
        return tx.execute(status -> {
            CardBalance b = balances.findForUpdate(cardId).orElseThrow();
            if (b.getAvailable() < amount) return HoldResult.declined(b.getAvailable(), "INSUFFICIENT_CREDIT");
            b.setAvailable(b.getAvailable() - amount);
            return HoldResult.approved(b.getAvailable());
        });
    }

    @Override
    public void release(String cardId, String authId, long amount) {
        jdbc.update("update card_balances set available = available + ? where card_id = ?", amount, cardId);
    }
}
