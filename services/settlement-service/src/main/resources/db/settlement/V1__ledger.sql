CREATE TABLE ledger_postings (
    id        BIGSERIAL PRIMARY KEY,
    auth_id   VARCHAR(36) NOT NULL UNIQUE,
    card_id   VARCHAR(36) NOT NULL,
    amount    BIGINT NOT NULL,
    strategy  VARCHAR(32) NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_card ON ledger_postings (card_id);

CREATE TABLE settlement_batches (
    id         BIGSERIAL PRIMARY KEY,
    card_id    VARCHAR(36) NOT NULL,
    day        DATE NOT NULL,
    total      BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (card_id, day)
);
