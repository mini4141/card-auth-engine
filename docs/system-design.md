# System design: high-traffic authorisation

## Requirements

| | |
|---|---|
| Hot path | `POST /authorizations`: decide approve/decline for one card purchase |
| Invariant | Σ approved holds on a card ≤ its credit limit, under any concurrency and across instances |
| Latency | p99 < 50 ms at normal load (redis-lua) |
| Durability | every decision audited (DynamoDB); every approval reaches the ledger (Kafka → Postgres) exactly once in effect |

## Capacity estimate

Assumptions for a mid-size issuer: 5 M active cards, 3 authorisations per card per day, peak = 10× average.

- Average: 5 M × 3 / 86,400 ≈ **175 auth/s**; peak ≈ **1,750 auth/s**.
- Redis Lua hold ≈ 4 Redis round-trips per authorisation (idempotency `SET NX`, profile cache read, hold script, decision write) ≈ 7,000 ops/s at peak, far below one Redis node's ~100k ops/s.
- DynamoDB audit: one 0.3 KB item per decision → 1,750 WCU at peak (on-demand), ≈ 45 GB/day raw. TTL it after the dispute window.
- Kafka: 1,750 msg/s × ~350 B ≈ 0.6 MB/s, trivial; partitions are for per-card ordering and consumer parallelism, not throughput.
- Postgres sees only the ledger write path (approvals, async) and card management, so it is *not* on the hot path with `redis-lua`.

The measured local numbers (docs/benchmarks.md) are bounded by a laptop running Docker Desktop; one auth-service instance on Fargate 0.5 vCPU is sized for ~hundreds of req/s, and the ALB scales out by adding tasks.

## Why the strategies behave the way they do

| Strategy | Where the serialisation happens | Cost |
|---|---|---|
| naive | nowhere: read-modify-write races | wrong |
| db-pessimistic | row lock in Postgres: one writer at a time per card | queueing on the lock + a held DB connection |
| db-optimistic | version check at commit: losers retry | wasted work grows with contention (livelock risk on a hot card) |
| redis-lock | Redisson lock in Redis around a DB read/write | two extra round-trips, lock contention, lease handling |
| redis-lua | Redis executes the script atomically (single-threaded) | one round-trip, no waiting, no retries |

## Hot-key handling

A single card is the hot key: a flash sale or a card-testing attack can send thousands of requests/s to one card.
- redis-lua keeps the hot key in one Redis shard and answers each request in microseconds; cap per-card rate at the edge if needed.
- DB strategies degrade (lock queues, retry storms) as the benchmark shows, which is why the hot path avoids them.
- Kafka is keyed by `cardId`, so one card maps to one partition: per-card ordering is preserved, and a hot card cannot starve others because other cards live on other partitions.
- If one card ever outgrows one Redis shard, split its available credit into N sub-buckets (each its own key) and try them in turn.

## Idempotency and retries

Every request carries `Idempotency-Key`. `SET idem:{key} PENDING NX EX 24h` claims it; the decision replaces `PENDING`. A retry (even on the other instance) reads the stored decision and never places a second hold. If processing fails before a decision, the key is released so the client can retry.

## Failure modes

| Failure | Behaviour |
|---|---|
| Redis down | authorisation fails closed (error, no approval): never approve without a hold. Failover to a replica in production. |
| Redis loses data (restart without persistence) | `available` key missing → script returns "not primed" → decline. Re-prime from Postgres limits + ledger via the reconciliation job. |
| Auth instance crashes between decision and Kafka publish | the hold exists in Redis but no event: reconciliation shows `redisHolds` without ledger. An outbox table would close this gap; deliberately out of scope here. |
| Kafka down | decisions still succeed; events are retried by the producer (acks=all, idempotent). Settlement lags, holds stay open. |
| Settlement crashes mid-batch | consumer offsets are only committed after processing; redelivery is a no-op thanks to `auth_id UNIQUE`, and `capture` is idempotent. |
| Poison message | 3 retries then `card.authorizations.dlq`. |
| DynamoDB throttled/down | audit writes retry 5× off the request path; the decision is unaffected and the failure is logged. |
| Two settlement consumers | partitions are assigned per consumer group; same card → same partition → serial per card. |

## Consistency model

Redis is authoritative for *available credit* on the hot path; Postgres is authoritative for *limits and the ledger*. They are reconciled, not transactionally coupled: `GET /admin/reconcile` checks for every card that `limit − redisAvailable = open holds + ledger postings (redis-lua)` and the same for the DB-balance strategies. Zero drift is the expected steady state; the naive strategy produces non-zero drift, which is how the incident is detected.

## Scaling out

- Auth: stateless, scale by task count (2 locally behind nginx, 2 Fargate tasks behind the ALB). Correctness does not depend on the instance count because the serialisation point is Redis.
- Settlement: scale consumers up to the partition count.
- Notification: single SSE fan-out node here; for more, put a Redis pub/sub or Kafka consumer-per-node in front of the SSE connections.
