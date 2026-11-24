# CLAUDE.md — card-auth-engine

These are the engineering rules for Claude Code on this repository. You work **fully autonomously**. Don't stop to ask the user questions. When something is ambiguous, pick the simplest option that keeps every stack item in §2 covered. Record it in `docs/DECISIONS.md` (one line: date, decision, reason) and keep going. Read `SPECS.md` end to end before writing any code. It is the source of truth for features, architecture and proof.

## 1. Project at a glance

| | |
|---|---|
| Project | Card Auth Engine: concurrency-safe credit-card authorisation microservices (Spring Boot, Redis, Kafka, PostgreSQL, DynamoDB) |
| Repo | public GitHub repo `card-auth-engine`. `origin` is already configured and the default branch is `main` |
| Target role | Backend Engineer, payments / credit card (Java Spring Boot, concurrency, distributed systems) |
| Pitch | Thousands of authorisations hit the same card at once, and the credit limit must never be exceeded. The engine compares four concurrency strategies under load (naive, DB pessimistic, DB optimistic, Redis Lua atomic hold), runs as multiple instances behind a load balancer, and streams approved authorisations through Kafka into settlement and notification services. |
| Priority | **Breadth of the tech stack beats depth of business logic.** Every technology in §2 must appear in running code and be visible in the proof. Full validation, edge cases and complete domain rules are out of scope. |

## 2. Mandatory tech stack

| Area | Technology |
|---|---|
| Language / framework | Java 21 (virtual threads enabled), **Spring Boot 3.5** (Web, Validation, Actuator, Data JPA, Data Redis, Kafka), RestClient / HTTP interfaces |
| RDBMS | **PostgreSQL 17** (cards, limits, ledger, settlement batches), Flyway |
| NoSQL | **Amazon DynamoDB** (authorisation log / event store keyed by card + time; DynamoDB Local in Phase 1) via AWS SDK v2 Enhanced Client |
| Distributed cache | **Redis 7**: Lua script atomic `hold` / `release` / `capture` of available credit, idempotency (`SET NX`), **Redisson** distributed lock (for the lock-based strategy), cache-aside for card profiles |
| Messaging | **Kafka**: `apache/kafka` KRaft locally → **Amazon MSK Serverless** (IAM auth via `aws-msk-iam-auth`), Spring Kafka; partition key = card ID for per-card ordering |
| Services | `auth-service` (hot path, 2 instances), `settlement-service` (consumes approvals, posts the ledger, nightly batch), `notification-service` (consumes, pushes SSE to the dashboard) |
| Concurrency & distributed computing | Strategy pattern for authorisation (`naive`, `db-pessimistic` (`SELECT … FOR UPDATE`), `db-optimistic` (`@Version` + retry), `redis-lua`), benchmark harness, multi-instance correctness test |
| Testing / load | JUnit 5, **Testcontainers** (Postgres, Redis, Kafka, DynamoDB Local), **Gatling** (Java DSL) concurrency simulations, Awaitility |
| Observability | Micrometer + Prometheus + Grafana (local), CloudWatch metrics/logs (AWS), JSON logs with trace IDs (Micrometer Tracing) |
| Dashboard | Small web dashboard (Spring Boot static page + htmx/vanilla JS + Chart.js): live authorisation stream (SSE), card limit gauges, strategy benchmark table |
| AWS | ECS Fargate (2× auth, settlement, notification), ALB, RDS PostgreSQL, ElastiCache Redis, DynamoDB, MSK Serverless, CloudWatch, ECR |
| IaC / CI | **Terraform** · GitHub Actions (Gradle build, unit + Testcontainers tests) |

Don't swap an item for an alternative. If a library is unavailable or broken, use the closest equivalent and log the swap in `docs/DECISIONS.md`.

## 3. Environment

- The host is a **Windows 11 PC**. Claude Code's shell is Git Bash, and the container runtime is Docker Desktop (WSL2 backend). Write automation as POSIX `bash` scripts under `scripts/` and use forward-slash paths. Add a `.gitattributes` with `* text=auto eol=lf` so scripts keep LF line endings.
- Prerequisites: JDK 21, Gradle (wrapper), Docker Desktop, Terraform, AWS CLI v2. If one is missing, install it with `winget` or the official installer and record the version in `docs/DECISIONS.md`.
- The `gh` CLI is logged in to the owner's GitHub account, and AWS CLI v2 is installed.
- Don't use Bitnami charts or images (their free catalogue has been retired). Use official upstream images and charts.

## 4. AWS account rules

- Profile: **`card-auth-engine-deployer`**. Always pass it explicitly, either as `--profile card-auth-engine-deployer` or by exporting `AWS_PROFILE=card-auth-engine-deployer` inside the scripts. Never use the default profile or any other profile.
- Region: **`ap-southeast-2`** for everything except global services such as CloudFront and IAM.
- The profile has `PowerUserAccess` + `IAMFullAccess`. Start Phase 2 with `aws sts get-caller-identity --profile card-auth-engine-deployer`. If that fails, stop and write the reason at the top of `PROGRESS.md`.
- Use AWS only. Where a requirement names Azure, GCP or another hyperscaler, use the AWS equivalent stated in `SPECS.md`.
- IaC: Terraform (`infra/`). Don't create resources by hand in the console: every cloud resource comes from code in `infra/`. Keep IaC state local and gitignored.
- Tag every resource with `Project=card-auth-engine`, `Ephemeral=true`, `ManagedBy=<tool>` and `Owner=alvieirvansyah`.
- Deployment runs **from this PC via scripts** (`scripts/deploy.sh`, `scripts/destroy.sh`). GitHub Actions only builds and tests, and never holds AWS credentials.

## 5. Phases and gates

Work strictly in order. A phase is done only when its proof exists (§8) and its items are ticked in `PROGRESS.md`. Commit and push at the end of every phase.

1. **Phase 0, scaffold.** Set up the repo layout from `SPECS.md` §4. Create `README.md`, `PROGRESS.md` (copy every checklist item from `SPECS.md` §2 and §9–§11 into it), `docs/DECISIONS.md`, `.gitignore`, `.gitattributes`, `.env.example` and the CI workflow.
2. **Phase 1, local.** Build every feature in `SPECS.md` §5. Everything runs on this PC with Docker and local tooling, and makes **no AWS calls**: use the local stand-ins listed in `SPECS.md`. Run the full test suite, then capture proof into `proof/phase1-local/`. Phase 1 counts as "ready for next" only when `scripts/test-all.sh` is green, the CI run on `main` is green, and the proof index is complete.
3. **Phase 2, AWS showcase.** Deploy with `scripts/deploy.sh`, run the smoke/e2e tests against the cloud endpoints and capture proof into `proof/phase2-cloud/`. Keep the stack alive only as long as capturing takes, with a target of **under 2 hours**.
4. **Phase 3, destroy.** Right after capturing, run `scripts/destroy.sh` and then `scripts/verify-destroyed.sh`, and save the output to `proof/phase3-destroy/`. This phase is **mandatory even if Phase 2 partly failed**. Never end a session with cloud resources running.
5. **Phase 4, write-up.** Write `docs/PORTFOLIO.md`. It needs the problem, an architecture diagram (Mermaid), a stack → requirement table, the key screenshots (relative links into `proof/`), what was hard, the approximate AWS cost and how to reproduce. This file becomes a technical page on alvieirvansyah.com.

## 6. Engineering rules

- Keep it simple and conventional: idiomatic layout for each language, small modules, no premature abstraction, no dead code.
- Configure through environment variables, with a committed `.env.example`. Real `.env` files are gitignored.
- **Never commit secrets.** That includes credentials, IaC state, `.terraform/`, `cdk.out/`, kubeconfigs, private keys and certificates. Generate local dev keys and certs into a gitignored `.local/` folder.
- Every HTTP service exposes a health endpoint (`/healthz` or the framework idiom) and logs structured JSON to stdout.
- Pin versions: commit lockfiles and pin container images to at least a minor tag.
- Use one command for each job:
  - `scripts/dev-up.sh` starts everything locally and `scripts/dev-down.sh` stops it.
  - `scripts/seed.sh` loads demo data. Start-up may run it automatically so that demos never start empty.
  - `scripts/test-all.sh` runs every test layer.
  - `scripts/capture-proof.sh <phase>` captures the proof for a phase.
- Keep `README.md` current. It covers what the project is, the architecture diagram, a quickstart, deploy/destroy, a link to `proof/README.md` and `docs/PORTFOLIO.md`, and the CI badge.
- Don't put the hiring company's name anywhere in the repo. Describe the target role generically.
- `docs/AI_USAGE.md`: briefly note how AI coding tools were used and how their output was reviewed and tested.

## 7. Testing rules

- Tests are **shallow by design**: one happy-path test per feature or flow is enough. Don't write exhaustive validation or edge-case tests.
- Required layers: JUnit unit (Lua hold script semantics via Testcontainers Redis, strategy selection, settlement posting), Testcontainers integration (authorise → Kafka → settlement ledger row + DynamoDB log item), the **concurrency correctness test** (1,000 parallel authorisations on one card across 2 app instances: for each safe strategy, total approved ≤ limit and ledger = holds), Gatling simulation with assertions.
- `scripts/test-all.sh` runs every local layer and exits non-zero on failure. CI runs every layer that doesn't need the cloud.
- Save test output as files (JUnit XML, an HTML report or a plain log) under the current phase's `proof/.../tests/`.

## 8. Proof rules

Proof is the deliverable. Use this folder layout:

```
proof/
  README.md                 # index: each artifact on one line, with what it proves
  phase1-local/
    screenshots/            # PNG, named NN-what-it-shows.png
    logs/                   # captured service logs and CLI output
    tests/                  # test reports
  phase2-cloud/
    screenshots/  logs/  tests/
    resources.txt           # deployed AWS resources (from IaC outputs / describe calls)
  phase3-destroy/
    verify-destroyed.txt
```

- Capture screenshots with a script (`scripts/capture-proof.sh`, driving Playwright headless), never by hand, so they can be reproduced. The screenshots listed in `SPECS.md` are the minimum.
- There is no AWS console login, so render AWS-side visuals from the CLI. For example, `aws cloudwatch get-metric-widget-image` renders a metrics graph as a PNG. Otherwise save CLI `describe`/`get` output as logs.
- Before saving logs or CLI output, redact 12-digit AWS account IDs (`sed -E 's/[0-9]{12}/<ACCOUNT_ID>/g'`), access keys, tokens, passwords and the deployer's public IP.
- Proof must show the system **working**, with real data flowing through it, and not just "deployed".

## 9. Cost and safety guardrails (AWS)

- Use the smallest viable sizes: Fargate 0.5 vCPU / 1 GB (auth × 2), 0.25 vCPU / 0.5 GB (others), RDS `db.t4g.micro`, ElastiCache `cache.t4g.micro`, DynamoDB on-demand, MSK Serverless (1 cluster, 3 topics, few partitions), one ALB.
- **No NAT gateways.** Also no multi-AZ databases, provisioned IOPS, reserved capacity or Savings Plans, Route 53 domains, or Marketplace subscriptions.
- Databases: `deletion_protection = false`, skip the final snapshot and keep backup retention minimal. S3 buckets are force-destroyable and ECR repos force-deletable. CloudWatch log groups get 1-day retention and are created by IaC, so they get destroyed with it.
- Public app endpoints are fine for the showcase. Keep databases private: their security groups admit only the app tier. Don't open SSH or database ports to `0.0.0.0/0` and don't use SSH at all. Use SSM if you need a shell.
- Don't send real emails or SMS, and don't call paid third-party APIs. Anything external is mocked unless `SPECS.md` says otherwise.
- `scripts/destroy.sh` must be idempotent and safe to re-run. It first deletes anything that would block the IaC destroy: scale ECS services to 0, delete the MSK Serverless cluster (Terraform), empty the ECR repos.
- `scripts/verify-destroyed.sh` lists anything still tagged `Project=card-auth-engine` (`aws resourcegroupstaggingapi get-resources`) and runs the specific checks in `SPECS.md` §11. It exits non-zero if anything billable remains. The tagging API can show resources for a few minutes after deletion, so confirm each hit with a direct describe call before failing.

## 10. Git and CI

- Use Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `infra:`, `chore:`). Commit small and often, and push to `origin main` at least at the end of every phase.
- `.github/workflows/ci.yml` runs build, lint and the cloud-free tests on every push and pull request. Keep it green.
- Never force-push `main`. Never commit build output, dependency folders (`node_modules`, `target`, `bin`, `obj`, `.venv`) or state files.

## 11. Project-specific rules

- **Correctness under concurrency is the headline.** The benchmark `scripts/bench-strategies.sh` runs the same Gatling scenario (for example 1,000 concurrent auths of ¥1,000 against a ¥100,000 limit) per strategy and records `approved_count`, `overspend_amount`, p50/p95/p99 latency and throughput into `docs/benchmarks.md`. The `naive` strategy must visibly overspend (that's the point). The three safe ones must show overspend = 0.
- Idempotency: every authorisation request carries `Idempotency-Key`. Redis `SET NX` stores the decision for 24 h. Retries return the same decision and never place a second hold.
- Hold lifecycle: `authorise` places a hold (Lua: if available ≥ amount then available -= amount and record the hold), `capture` (settlement) converts the hold into a ledger posting, and `reverse` releases it. A reconciliation job compares Redis holds with Postgres and logs any drift (expected: none).
- Ordering: Kafka topic `card.authorizations` keyed by card ID. Consumers are idempotent (dedupe by auth ID in Postgres, unique constraint).
- Virtual threads on (`spring.threads.virtual.enabled=true`). Explain the impact in `docs/benchmarks.md` (with a before/after run on the `db-pessimistic` strategy).
- Multi-instance is mandatory in both phases (2 `auth-service` instances: compose scale=2 behind an nginx round-robin locally, and 2 Fargate tasks behind the ALB). Correctness claims are tested across instances, not just threads.

## 12. Definition of done

- [ ] Every stack item in §2 is used in running code. Cross-check with the coverage matrix in `SPECS.md` §2 and tick each row in `PROGRESS.md`.
- [ ] Phase 1 proof is complete, and all tests are green locally and in CI.
- [ ] Phase 2 proof is complete: cloud screenshots, a cloud test run and the resource list.
- [ ] Phase 3: everything is destroyed and `verify-destroyed.txt` shows nothing billable left.
- [ ] `docs/PORTFOLIO.md` and `README.md` are written, and everything is committed and pushed.
