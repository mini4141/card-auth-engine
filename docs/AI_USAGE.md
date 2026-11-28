# AI usage

This project was built end to end with Claude Code (an AI coding agent) working from `SPECS.md` and `CLAUDE.md`.

**What the AI did:** scaffolded the repository, wrote the three Spring Boot services, the strategies, Lua scripts, Terraform, scripts, dashboard and documentation, and ran the builds, tests, benchmarks and the AWS deploy/destroy.

**How the output was reviewed and tested:**
- Every behaviour claim is backed by a test or a captured artefact under `proof/`: Lua hold semantics against a real Redis, ledger idempotency against a real Postgres, the authorise → Kafka → ledger + DynamoDB flow, and a 1,000-request / 2-instance concurrency test that asserts the three safe strategies never exceed the limit *and* that the naive one does.
- Benchmark numbers in `docs/benchmarks.md` come from `scripts/bench-strategies.sh` runs, not from estimates; the incident write-up quotes log lines captured from those runs.
- Failures found along the way (Flyway sharing one schema, Docker 29 vs Testcontainers, virtual-thread/blocking-SDK starvation, Docker Desktop connection limits) were root-caused and recorded in `docs/DECISIONS.md` rather than papered over.
- AWS resources are created only from `infra/` and destroyed by `scripts/destroy.sh`; `scripts/verify-destroyed.sh` output is committed as proof.
- Nothing the AI generated is trusted because it compiled: CI runs the full build and test suite on every push.
