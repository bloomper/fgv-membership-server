-- How a bank export looks: which columns hold what. Admins describe each bank once.
CREATE TABLE bank_format
(
    pk             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id             CHAR(36)     NOT NULL,
    name           VARCHAR(100) NOT NULL,
    charset        VARCHAR(30)  NOT NULL DEFAULT 'UTF-8',
    column_separator VARCHAR(1) NOT NULL DEFAULT ';',
    header_lines   INT          NOT NULL DEFAULT 1,
    date_column    INT          NOT NULL,
    amount_column  INT          NOT NULL,
    text_column    INT          NOT NULL,
    payer_column   INT          NULL,
    date_format    VARCHAR(30)  NOT NULL DEFAULT 'yyyy-MM-dd',
    decimal_comma  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6)  NOT NULL,
    CONSTRAINT uq_bank_format_id UNIQUE (id),
    CONSTRAINT uq_bank_format_name UNIQUE (name)
);

-- Incoming transactions from uploaded statements. Only incoming ones are kept. The fingerprint stops the same transaction
-- from being imported twice; a dismissed transaction keeps only that.
CREATE TABLE bank_transaction
(
    pk           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id           CHAR(36)     NOT NULL,
    booked_on    DATE         NOT NULL,
    amount_ore   BIGINT       NOT NULL,
    text         VARCHAR(500) NULL,
    payer        VARCHAR(200) NULL,
    fingerprint  CHAR(64)     NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    file_name    VARCHAR(200) NULL,
    imported_by  VARCHAR(36)  NOT NULL,
    imported_at  DATETIME(6)  NOT NULL,
    dismissed_at DATETIME(6)  NULL,
    CONSTRAINT uq_bank_transaction_id UNIQUE (id),
    CONSTRAINT uq_bank_transaction_fingerprint UNIQUE (fingerprint)
);

CREATE INDEX ix_bank_transaction_status ON bank_transaction (status, booked_on);

-- A payment may come from a bank transaction, which may pay for several requests (two members paid together)
ALTER TABLE payment
    ADD COLUMN transaction_pk BIGINT NULL,
    ADD CONSTRAINT fk_payment_transaction FOREIGN KEY (transaction_pk) REFERENCES bank_transaction (pk);
