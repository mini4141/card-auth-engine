# INC-001: card approved far beyond its credit limit (naive strategy lost-update race)

| | |
|---|---|
| Severity | SEV-2 (financial exposure: approvals above the limit) |
| Detected by | `ReconcileService` / `scripts/reconcile.sh`: `dbDrift > 0` and `overspentCards > 0` |
| Affected | cards authorised with `X-Strategy: naive` (staging benchmark traffic; this is a reproduction, not production data) |
| Status | Root-caused; fixed by routing the hot path to a serialised strategy (`redis-lua`). `naive` is kept only as the benchmark baseline. |

## Symptom

During the 1,000-request burst against a ¥100,000 card, the naive strategy **approved all 1,000 requests: ¥1,000,000 against a ¥100,000 limit (overspend ¥900,000)**. Every safe strategy approved exactly 100.
([docs/benchmarks.md](../benchmarks.md))

Reconciliation flagged it immediately:

```
cardId  limit   redisAvail redisHolds redisLedger redisDrift dbAvail dbLedger dbDrift
309afae9…  100000  100000     0          0           0          55000   1000000  955000   DRIFT (lost updates: naive strategy)
```

The ledger (what we told merchants) holds ¥1,000,000 of approvals, while the balance table says only ¥45,000 was ever taken: the balance and the approvals disagree by ¥955,000.

## Timeline of the reproduction (small scale)

`scripts/capture-naive-race.sh`: 40 concurrent ¥1,000 requests, card limit ¥5,000 (at most 5 may be approved), both auth-service instances. Full log: [proof/phase1-local/logs/naive-race-interleaving.log](../../proof/phase1-local/logs/naive-race-interleaving.log).

```
10:27:12.340596Z auth-service-1  naive READ  auth=daf9d624…  available=5000
10:27:12.340689Z auth-service-1  naive READ  auth=1fa0eb59…  available=5000
10:27:12.341060Z auth-service-1  naive READ  auth=134c3b77…  available=5000
   … 20 requests on each instance all read available=5000 before anyone wrote …
10:27:12.368654Z auth-service-1  naive WRITE auth=1fa0eb59…  wrote=4000
10:27:12.369887Z auth-service-1  naive WRITE auth=24c0d0a3…  wrote=4000
10:27:12.374373Z auth-service-1  naive WRITE auth=f82cda43…  wrote=4000
```

All 40 requests read `available=5000`; the 40 writes only ever wrote `4000` or `3000` (each computed from its own stale read). All 40 were approved: ¥40,000 approved against ¥5,000, while the row ends at ¥3,000 available.

## Root cause

`NaiveStrategy.hold` does two separate statements outside any transaction or lock:

```java
Long available = jdbc.queryForObject("select available from card_balances where card_id = ?", ...);   // (1) read
if (available >= amount)
    jdbc.update("update card_balances set available = ? where card_id = ?", available - amount, ...);  // (2) write a value computed from (1)
```

Classic **check-then-act / lost update**: between (1) and (2) any number of other requests, on this instance *or the other one*, can read the same stale balance. Each then writes `stale - amount`, overwriting the others' decrements, so the balance barely moves while every request is approved. Adding more instances only widens the window, and a JVM `synchronized` would not help because the race is between processes.

Contributing factors: the default isolation level (READ COMMITTED) does not protect a read-then-write sequence, and there was no invariant check at write time (`UPDATE … WHERE available >= amount`).

## Why it was detectable

The ledger is written from the *decision events* (Kafka → settlement) while the balance is written by the decision path, so they are independent records of the same fact. Reconciliation compares them per card; any lost update shows up as drift.

## Fix and prevention

- Hot path default is `redis-lua`: check-and-decrement in a single atomic Redis script. The other safe strategies (`db-pessimistic` `SELECT … FOR UPDATE`, `db-optimistic` `@Version` + retry, `redis-lock` Redisson lock) serialise the same critical section differently; all four are asserted in `ConcurrencyCorrectnessIT` (1,000 parallel requests across 2 instances: approved ≤ limit, ledger = holds).
- `ConcurrencyCorrectnessIT.naiveStrategyOverspends` asserts the opposite for `naive`, so the benchmark baseline cannot silently become "safe" or the test suite silently stop covering the race.
- `ReconcileService` runs every minute and logs `totalAbsDrift` / `overspentCards`; alert on non-zero.
- A cheap defence-in-depth for any SQL strategy: make the invariant part of the statement (`UPDATE … SET available = available - ? WHERE card_id = ? AND available >= ?` and check the row count).
