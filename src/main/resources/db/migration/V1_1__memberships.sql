-- FGV memberships: a spexare's membership for one year, or for life from a first year.
-- The spexare is Spexregister's UUID; names and contact details are never stored here.
CREATE TABLE membership
(
    pk              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id              CHAR(36)     NOT NULL,
    spexare_id      CHAR(36)     NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    membership_year INT          NOT NULL,
    lifetime        BOOLEAN      NOT NULL DEFAULT FALSE,
    status          VARCHAR(20)  NOT NULL,
    how             VARCHAR(20)  NULL,
    note            VARCHAR(500) NULL,
    spexare_deleted BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uq_membership_id UNIQUE (id),
    CONSTRAINT uq_membership_spexare_type_year UNIQUE (spexare_id, type, membership_year)
);

CREATE INDEX ix_membership_year_status ON membership (membership_year, status);

-- The feed (spec section 9): changes to memberships that count, in order
CREATE TABLE membership_change
(
    sequence        BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    membership_id   CHAR(36)    NOT NULL,
    spexare_id      CHAR(36)    NOT NULL,
    type            VARCHAR(20) NOT NULL,
    membership_year INT         NOT NULL,
    lifetime        BOOLEAN     NOT NULL DEFAULT FALSE,
    kind            VARCHAR(10) NOT NULL,
    at              DATETIME(6) NOT NULL
);

-- Who reads the feed, and how far
CREATE TABLE sync_consumer
(
    client_id       VARCHAR(100) NOT NULL PRIMARY KEY,
    acknowledged    BIGINT       NOT NULL,
    acknowledged_at DATETIME(6)  NOT NULL
);
