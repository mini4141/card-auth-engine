# ADR 0002: Kafka topic keyed by card ID, idempotent consumers

**Status:** accepted

**Context:** approvals must reach the ledger and the dashboard; settlement must see a card's events in order and tolerate redelivery.

**Decision:** publish every decision to `card.authorizations` with the card ID as the record key (same card → same partition → ordered). Consumers are idempotent: settlement inserts into `ledger_postings` with `auth_id UNIQUE` (`ON CONFLICT DO NOTHING`) and the Redis `capture` script is a no-op when the hold is gone. Poison messages are retried 3× then sent to `card.authorizations.dlq`. Settlement publishes `card.settlements` for the notification service.

**Consequences:** at-least-once delivery with exactly-once *effect*; an outbox would be needed to also cover a crash between the Redis decision and the Kafka publish (documented in `system-design.md`).
