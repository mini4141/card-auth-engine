# PROGRESS

Resume point: see the first unchecked item. Phases per CLAUDE.md §5.

## Phases
- [x] Phase 0 scaffold (repo layout, Gradle wrapper, docs skeleton, CI)
- [ ] Phase 1 local (features, tests, proof)
- [ ] Phase 2 AWS showcase
- [ ] Phase 3 destroy + verify
- [ ] Phase 4 write-up (docs/PORTFOLIO.md)

## Coverage matrix (SPECS §2)
- [ ] Large-scale systems: 3 services + infra + benchmarks
- [ ] Infra for scale: Redis atomic holds, Kafka partitioning, DynamoDB log, ALB scale-out
- [ ] Triage/RCA: docs/incidents/INC-001-overspend-naive.md + reconciliation job
- [ ] Spring Boot professional use (CI green)
- [ ] RDBMS + NoSQL + distributed cache
- [ ] Strategy pattern, limit maths, domain model
- [ ] Concurrency: 4 strategies, multi-instance test, virtual threads, Redisson lock
- [ ] REST, Pub/Sub, DB clients
- [ ] docs/system-design.md
- [ ] Microservices + event-driven

## Stack coverage (CLAUDE.md §2)
- [ ] Java 21 + virtual threads + Spring Boot 3.5 (Web, Validation, Actuator, JPA, Redis, Kafka), RestClient
- [ ] PostgreSQL 17 + Flyway
- [ ] DynamoDB (Local → AWS) via SDK v2 Enhanced Client
- [ ] Redis 7: Lua hold/release/capture, SET NX idempotency, Redisson lock, cache-aside
- [ ] Kafka KRaft → MSK Serverless (IAM), key = cardId
- [ ] auth-service x2, settlement-service, notification-service
- [ ] Strategies + benchmark harness + multi-instance correctness test
- [ ] JUnit 5, Testcontainers, Gatling, Awaitility
- [ ] Micrometer + Prometheus + Grafana, CloudWatch, JSON logs w/ trace IDs
- [ ] Dashboard (SSE, gauges, benchmark table, Chart.js)
- [ ] AWS: ECS Fargate, ALB, RDS, ElastiCache, DynamoDB, MSK Serverless, CloudWatch, ECR
- [ ] Terraform + GitHub Actions

## SPECS §9 Phase 1 proof
- [ ] tests/: JUnit XML (unit + Testcontainers + correctness), Gatling HTML per strategy
- [ ] docs/benchmarks.md, docs/incidents/INC-001-overspend-naive.md
- [ ] screenshots 01-dashboard-live, 02-card-gauges, 03-benchmark-table, 04-grafana-latency-by-strategy, 05-gatling-redis-lua
- [ ] logs: naive-race-interleaving.log, reconcile.txt, dynamodb-auth-log-query.json, kafka-consume.txt

## SPECS §10 Phase 2 proof
- [ ] screenshots 01-cloud-dashboard-live, 02-cloudwatch-latency
- [ ] tests/gatling-cloud/, logs/cloud-benchmarks.json, logs/cloud-reconcile.txt, logs/msk-describe.json, resources.txt

## SPECS §11 Phase 3
- [ ] terraform destroy done, verify-destroyed.txt shows nothing billable

## Definition of done (CLAUDE.md §12)
- [ ] stack covered  - [ ] phase1 proof + CI green  - [ ] phase2 proof  - [ ] phase3 destroyed  - [ ] PORTFOLIO.md + README pushed
