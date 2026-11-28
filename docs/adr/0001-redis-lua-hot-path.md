# ADR 0001: Redis Lua script as the hot-path concurrency control

**Status:** accepted

**Context:** thousands of authorisations can hit one card concurrently across several service instances; the limit must never be exceeded and p99 must stay low.

**Decision:** keep available credit in Redis and place holds with a single Lua script (`hold.lua`). Redis runs scripts atomically on a single thread, so check-and-decrement cannot interleave. Keep the other strategies (naive, db-pessimistic, db-optimistic, redis-lock) in the code base behind a `AuthorizationStrategy` interface, selectable per request, so the trade-offs are measured rather than argued.

**Consequences:**
- one round-trip, no waiting, no retries; instance count does not affect correctness.
- Redis becomes a critical dependency for the hot path (fail closed if unavailable) and must be reconciled with Postgres, hence `ReconcileService`.
- DB-based strategies remain useful when a team cannot add Redis, and as the baseline in the benchmark.
