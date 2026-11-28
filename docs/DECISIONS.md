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
