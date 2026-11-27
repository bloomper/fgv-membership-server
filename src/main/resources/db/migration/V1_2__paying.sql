-- What a membership costs: per type and year, or the one-time fee for a life membership (year is then NULL)
CREATE TABLE fee_level
(
    pk         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id         CHAR(36)     NOT NULL,
    type       VARCHAR(20)  NOT NULL,
    fee_year   INT          NULL,
    lifetime   BOOLEAN      NOT NULL DEFAULT FALSE,
    name_sv    VARCHAR(100) NOT NULL,
    name_en    VARCHAR(100) NOT NULL,
    amount     INT          NOT NULL,
    retired    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT uq_fee_level_id UNIQUE (id)
);

CREATE INDEX ix_fee_level_year ON fee_level (fee_year, retired);

-- A request to pay for one or more memberships; the reference is what the payer's Swish app or bank carries
CREATE TABLE payment_request
(
    pk               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id               CHAR(36)    NOT NULL,
    reference        VARCHAR(12) NOT NULL,
    spexare_id       CHAR(36)    NOT NULL,
    amount           INT         NOT NULL,
    status           VARCHAR(20) NOT NULL,
    created_by       VARCHAR(36) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    reported_at      DATETIME(6) NULL,
    paid_by          VARCHAR(200) NULL,
    reminder_sent_at DATETIME(6) NULL,
    CONSTRAINT uq_payment_request_id UNIQUE (id),
    CONSTRAINT uq_payment_request_reference UNIQUE (reference)
);

CREATE INDEX ix_payment_request_spexare ON payment_request (spexare_id, created_at);
CREATE INDEX ix_payment_request_status ON payment_request (status, created_at);

CREATE TABLE payment_request_membership
(
    request_pk    BIGINT   NOT NULL,
    membership_id CHAR(36) NOT NULL,
    PRIMARY KEY (request_pk, membership_id),
    CONSTRAINT fk_prm_request FOREIGN KEY (request_pk) REFERENCES payment_request (pk)
);

-- Money that arrived for a request, recorded by the treasurer (and later matched from bank statements)
CREATE TABLE payment
(
    pk          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id          CHAR(36)     NOT NULL,
    request_pk  BIGINT       NOT NULL,
    paid_on     DATE         NOT NULL,
    amount      INT          NOT NULL,
    how         VARCHAR(20)  NOT NULL,
    recorded_by VARCHAR(36)  NOT NULL,
    note        VARCHAR(500) NULL,
    recorded_at DATETIME(6)  NOT NULL,
    CONSTRAINT uq_payment_id UNIQUE (id),
    CONSTRAINT fk_payment_request FOREIGN KEY (request_pk) REFERENCES payment_request (pk)
);
