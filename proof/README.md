# Proof index

Everything here is produced by scripts (`scripts/capture-proof.sh`, `scripts/test-all.sh`, `scripts/bench-strategies.sh`, `scripts/deploy.sh`, `scripts/destroy.sh`), with account IDs and secrets redacted.

## Phase 1: local (Docker Compose, 2× auth-service behind nginx)

| Artifact | What it proves |
|---|---|
| [phase1-local/screenshots/01-dashboard-live.png](phase1-local/screenshots/01-dashboard-live.png) | Live authorisation stream over SSE (APPROVED then SETTLED rows), counters and rate moving during load |
| [phase1-local/screenshots/02-card-gauges.png](phase1-local/screenshots/02-card-gauges.png) | Card limit gauges: available vs used dropping as approvals land |
| [phase1-local/screenshots/03-benchmark-table.png](phase1-local/screenshots/03-benchmark-table.png) | Strategy benchmark table (naive overspend ¥900,000 vs ¥0 for safe strategies) |
| [phase1-local/screenshots/04-grafana-latency-by-strategy.png](phase1-local/screenshots/04-grafana-latency-by-strategy.png) | Prometheus/Grafana p99 latency by strategy and requests split across both auth instances |
| [phase1-local/screenshots/05-gatling-redis-lua.png](phase1-local/screenshots/05-gatling-redis-lua.png) | Gatling report for the redis-lua burst (1,000 requests, 0 failures) |
| [phase1-local/logs/naive-race-interleaving.log](phase1-local/logs/naive-race-interleaving.log) | Interleaved READ/WRITE log lines from both instances: 40 requests all read `available=5000` (INC-001 evidence) |
| [phase1-local/logs/reconcile.txt](phase1-local/logs/reconcile.txt) | Reconciliation (Redis vs Postgres vs ledger): zero drift on every safe-strategy card, drift only on naive cards |
| [phase1-local/logs/dynamodb-auth-log-query.json](phase1-local/logs/dynamodb-auth-log-query.json) | DynamoDB audit items for one card (approvals + a decline), newest first |
| [phase1-local/logs/kafka-consume.txt](phase1-local/logs/kafka-consume.txt) | `kafka-console-consumer` on `card.authorizations`, key = cardId, plus topic partitions |
| [phase1-local/logs/settlement-ledger-posts.txt](phase1-local/logs/settlement-ledger-posts.txt) | Settlement service posting ledger rows from Kafka events |
| [phase1-local/tests/junit/](phase1-local/tests/junit/) | JUnit XML: Lua hold semantics, strategy selection, ledger posting, authorise→Kafka→ledger+DynamoDB, 2-instance concurrency correctness |
| [phase1-local/tests/gatling/](phase1-local/tests/gatling/) | Gatling HTML report from `scripts/test-all.sh` (assertions: no failed requests; correctness: exactly 100 approved, ¥0 overspend) |
| [phase1-local/tests/gatling-*/](phase1-local/tests/) | Gatling reports per strategy (burst and steady) from `scripts/bench-strategies.sh` |
| [../docs/benchmarks.md](../docs/benchmarks.md) | Numbers: approved count, overspend, p50/p95/p99, throughput, virtual-thread comparison |
| [../docs/incidents/INC-001-overspend-naive.md](../docs/incidents/INC-001-overspend-naive.md) | Root-cause write-up of the naive overspend |

## Phase 2: AWS (ECS Fargate, ALB, RDS, ElastiCache, DynamoDB, MSK Serverless)

| Artifact | What it proves |
|---|---|
| [phase2-cloud/resources.txt](phase2-cloud/resources.txt) | Deployed ECS services (2× auth, settlement, notification), ALB, RDS, ElastiCache, DynamoDB, MSK Serverless, ECR, log groups, tagged resources |
| [phase2-cloud/screenshots/01-cloud-dashboard-live.png](phase2-cloud/screenshots/01-cloud-dashboard-live.png) | The dashboard served through the ALB with APPROVED and SETTLED rows flowing through MSK Serverless (its benchmark table is the local run bundled in the image) |
| [phase2-cloud/screenshots/02-cloudwatch-latency.png](phase2-cloud/screenshots/02-cloudwatch-latency.png) | `aws cloudwatch get-metric-widget-image`: auth.latency.max per strategy from the Micrometer CloudWatch registry |
| [phase2-cloud/logs/cloud-benchmarks.json](phase2-cloud/logs/cloud-benchmarks.json) (+ `.md`) | Same benchmark suite against the ALB (300 concurrent / 50 req/s): naive overspends, safe strategies approve exactly 100 |
| [phase2-cloud/logs/cloud-reconcile.txt](phase2-cloud/logs/cloud-reconcile.txt) | Cloud reconciliation: zero drift on safe-strategy cards |
| [phase2-cloud/logs/msk-describe.json](phase2-cloud/logs/msk-describe.json) | MSK Serverless cluster with IAM auth, ACTIVE |
| [phase2-cloud/logs/cloud-dynamodb-auth-log-query.json](phase2-cloud/logs/cloud-dynamodb-auth-log-query.json) | DynamoDB audit items read back through the API in AWS (plus [scan count](phase2-cloud/logs/cloud-dynamodb-scan-count.json)) |
| [phase2-cloud/tests/gatling-cloud/](phase2-cloud/tests/gatling-cloud/) | Gatling reports from the cloud run |

## Phase 3: destroy

[phase3-destroy/verify-destroyed.txt](phase3-destroy/verify-destroyed.txt): per-service describe checks plus the tagging-API listing, showing nothing billable remains.
