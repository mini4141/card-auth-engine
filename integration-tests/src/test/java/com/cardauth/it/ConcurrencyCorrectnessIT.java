package com.cardauth.it;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * 1,000 parallel authorisations of 1,000 yen on ONE card with a 100,000 yen limit, spread across two
 * auth-service instances behind a CountDownLatch start gate, issued from 1,000 virtual threads.
 */
class ConcurrencyCorrectnessIT {
    static final int REQUESTS = 1000;
    static final long AMOUNT = 1000, LIMIT = 100_000;

    @BeforeAll
    static void up() { Stack.start(); }

    record Outcome(int approved, String cardId) {}

    private Outcome burst(String strategy) throws Exception {
        String card = Stack.createCard(LIMIT).get("id").asText();
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger approved = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < REQUESTS; i++) {
                final int n = i;
                futures.add(vts.submit(() -> {
                    gate.await();
                    if ("APPROVED".equals(Stack.authorise(n, card, AMOUNT, strategy, null).get("decision").asText())) approved.incrementAndGet();
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) f.get();
        }
        return new Outcome(approved.get(), card);
    }

    @ParameterizedTest
    @ValueSource(strings = {"db-pessimistic", "db-optimistic", "redis-lock", "redis-lua"})
    void safeStrategiesNeverExceedTheLimitAndLedgerMatchesHolds(String strategy) throws Exception {
        Outcome o = burst(strategy);

        assertTrue(o.approved() * AMOUNT <= LIMIT, strategy + " overspent: approved=" + o.approved());
        assertEquals(LIMIT / AMOUNT, o.approved(), strategy + " should approve exactly limit/amount");
        // settlement catches up through Kafka: ledger total == sum of approved holds
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertEquals(o.approved() * AMOUNT, Stack.queryLong("select coalesce(sum(amount),0) from ledger_postings where card_id = '" + o.cardId() + "'")));
        if (strategy.equals("redis-lua")) {
            assertEquals(0, Stack.get(Stack.base(0) + "/cards/" + o.cardId()).get("holds").asLong()); // every hold captured
        }
    }

    @Test
    void naiveStrategyOverspends() throws Exception {
        Outcome o = burst("naive");
        // the expected failure: a lost-update race lets more than limit/amount through
        assertTrue(o.approved() * AMOUNT > LIMIT, "naive was expected to overspend but approved=" + o.approved());
    }
}
