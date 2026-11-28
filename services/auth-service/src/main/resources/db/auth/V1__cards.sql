CREATE TABLE cards (
    id           VARCHAR(36) PRIMARY KEY,
    holder       VARCHAR(120) NOT NULL,
    credit_limit BIGINT NOT NULL,
    version      BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE card_balances (
    card_id   VARCHAR(36) PRIMARY KEY REFERENCES cards (id),
    available BIGINT NOT NULL,
    version   BIGINT NOT NULL DEFAULT 0
);
