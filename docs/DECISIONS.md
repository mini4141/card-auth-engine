# Decisions

Format: date, decision, reason.

- 2026-11-28 Gradle wrapper 8.14.3 generated from a downloaded distribution. No Gradle on PATH.
- 2026-11-28 Tool versions: JDK 21.0.9, Docker 29.7.2, Terraform 1.17.2, AWS CLI 2.42.1, Node 24 (for Playwright proof capture).
- 2026-11-28 Testcontainers pinned to 1.21.4. Docker 29 needs a newer docker-java than Boot 3.5 manages.
- 2026-11-28 Added a fifth strategy `redis-lock` (Redisson lock around the naive read/write) so the Redisson lock has a lock-based strategy to serve. Limit changes also take the Redisson lock.
- 2026-11-28 Single `auth-service` owns Flyway for cards/card_balances; settlement-service has its own Flyway history table and tables. Reconciliation reads ledger via JDBC.
- 2026-11-28 Amounts are whole yen (long). SPECS uses ¥ as the currency.
- 2026-11-28 Decision publish to Kafka is async after the response is built (no outbox). Crash between decision and publish can lose an event. Accepted, outbox is out of scope.
- 2026-11-28 Integration/correctness tests run two auth-service Spring contexts in one JVM against shared Testcontainers; services use distinct `spring.config.name` so they can co-exist on one classpath.
- 2026-11-28 ElastiCache and Kafka client traffic uses plain TCP inside the VPC for Redis (TLS off) to keep the showcase simple; MSK uses TLS + IAM.
- 2026-11-28 Postgres is published on host port 15432 (5432 is taken by another local project's container).
- 2026-11-28 Both Flyway histories (`flyway_auth`, `flyway_settlement`) use `baseline-on-migrate` with baseline version 0 because the two services share the `public` schema and neither can assume it is empty.
- 2026-11-28 Testcontainers is pinned to Docker API 1.44 (`api.version` system property + `DOCKER_API_VERSION`) because Docker 29 rejects docker-java's default 1.32.
- 2026-11-28 Audit/Kafka publishing runs on an 8-thread platform pool, not virtual threads: the blocking AWS SDK call pinned virtual-thread carriers and starved request handling under burst.
- 2026-11-28 Gatling injects the 1,000-user burst over 3 s (`RAMP_SECONDS`): Docker Desktop refuses part of a simultaneous 1,000-connection SYN burst, which made the run fail with connection-refused instead of measuring the service. Measured latencies are bounded by Docker Desktop networking (see docs/benchmarks.md); the p99 < 50 ms target is therefore only reported at the steady rate, not asserted.
- 2026-11-28 Gatling percentiles are computed inside the simulation (response-time checks) because Gatling 3.14 reports no longer ship `js/stats.json`.
- 2026-11-28 Cloud: Fargate tasks run in public subnets with public IPs (no NAT gateway); RDS/ElastiCache/MSK sit in private subnets with no internet route; DB credentials come from `random_password` into the (local, gitignored) Terraform state and task env instead of Secrets Manager, to keep the ephemeral showcase simple.
- 2026-11-28 CI has a second job that runs the Gatling burst against the compose stack (`ONLY_GATLING=1 scripts/test-all.sh`).
