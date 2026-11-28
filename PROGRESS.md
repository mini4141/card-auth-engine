# PROGRESS

Resume point: see the first unchecked item. Phases per CLAUDE.md §5.

## Phases
- [x] Phase 0 scaffold (repo layout, Gradle wrapper, docs skeleton, CI)
- [x] Phase 1 local (features, tests, proof)
- [x] Phase 2 AWS showcase
- [x] Phase 3 destroy + verify
- [x] Phase 4 write-up (docs/PORTFOLIO.md)

## Coverage matrix (SPECS §2)
- [x] Large-scale systems: 3 services + infra + benchmarks
- [x] Infra for scale: Redis atomic holds, Kafka partitioning, DynamoDB log, ALB scale-out
- [x] Triage/RCA: docs/incidents/INC-001-overspend-naive.md + reconciliation job
- [x] Spring Boot professional use (CI green)
- [x] RDBMS + NoSQL + distributed cache
- [x] Strategy pattern, limit maths, domain model
- [x] Concurrency: 4 strategies, multi-instance test, virtual threads, Redisson lock
- [x] REST, Pub/Sub, DB clients
- [x] docs/system-design.md
- [x] Microservices + event-driven

## Stack coverage (CLAUDE.md §2)
- [x] Java 21 + virtual threads + Spring Boot 3.5 (Web, Validation, Actuator, JPA, Redis, Kafka), RestClient
- [x] PostgreSQL 17 + Flyway
- [x] DynamoDB (Local → AWS) via SDK v2 Enhanced Client
- [x] Redis 7: Lua hold/release/capture, SET NX idempotency, Redisson lock, cache-aside
- [x] Kafka KRaft → MSK Serverless (IAM), key = cardId
- [x] auth-service x2, settlement-service, notification-service
- [x] Strategies + benchmark harness + multi-instance correctness test
- [x] JUnit 5, Testcontainers, Gatling, Awaitility
- [x] Micrometer + Prometheus + Grafana, CloudWatch, JSON logs w/ trace IDs
- [x] Dashboard (SSE, gauges, benchmark table, Chart.js)
- [x] AWS: ECS Fargate, ALB, RDS, ElastiCache, DynamoDB, MSK Serverless, CloudWatch, ECR
- [x] Terraform + GitHub Actions

## SPECS §9 Phase 1 proof
- [x] tests/: JUnit XML (unit + Testcontainers + correctness), Gatling HTML per strategy
- [x] docs/benchmarks.md, docs/incidents/INC-001-overspend-naive.md
- [x] screenshots 01-dashboard-live, 02-card-gauges, 03-benchmark-table, 04-grafana-latency-by-strategy, 05-gatling-redis-lua
- [x] logs: naive-race-interleaving.log, reconcile.txt, dynamodb-auth-log-query.json, kafka-consume.txt

## SPECS §10 Phase 2 proof
- [x] screenshots 01-cloud-dashboard-live, 02-cloudwatch-latency
- [x] tests/gatling-cloud/, logs/cloud-benchmarks.json, logs/cloud-reconcile.txt, logs/msk-describe.json, resources.txt

## SPECS §11 Phase 3
- [x] terraform destroy done, verify-destroyed.txt shows nothing billable

## Definition of done (CLAUDE.md §12)
- [x] stack covered  - [x] phase1 proof + CI green  - [x] phase2 proof  - [x] phase3 destroyed  - [x] PORTFOLIO.md + README pushed
