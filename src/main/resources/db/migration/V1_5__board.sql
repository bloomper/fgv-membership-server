-- Personal links: a long random token that opens one payment request, and nothing else. Only its hash is kept.
CREATE TABLE personal_link
(
    pk         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_pk BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_personal_link_token UNIQUE (token_hash),
    CONSTRAINT fk_personal_link_request FOREIGN KEY (request_pk) REFERENCES payment_request (pk)
);

-- Renewal reminders sent by the board
CREATE TABLE reminder_campaign
(
    pk           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id           CHAR(36)     NOT NULL,
    campaign_year INT         NOT NULL,
    fee_level_id CHAR(36)     NOT NULL,
    sent_by      VARCHAR(36)  NOT NULL,
    sent_at      DATETIME(6)  NOT NULL,
    recipients   INT          NOT NULL,
    skipped      INT          NOT NULL,
    CONSTRAINT uq_reminder_campaign_id UNIQUE (id)
);

CREATE TABLE reminder_email
(
    pk          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    campaign_pk BIGINT      NULL,
    spexare_id  CHAR(36)    NOT NULL,
    request_id  CHAR(36)    NULL,
    kind        VARCHAR(20) NOT NULL,
    sent_at     DATETIME(6) NOT NULL,
    CONSTRAINT fk_reminder_email_campaign FOREIGN KEY (campaign_pk) REFERENCES reminder_campaign (pk)
);

CREATE INDEX ix_reminder_email_spexare ON reminder_email (spexare_id, sent_at);

-- Members who chose "don't remind me again"
CREATE TABLE reminder_opt_out
(
    spexare_id CHAR(36)    NOT NULL PRIMARY KEY,
    at         DATETIME(6) NOT NULL
);

-- Who changed what, for the changes that are not payments themselves
CREATE TABLE audit_entry
(
    pk      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor   VARCHAR(36)  NOT NULL,
    action  VARCHAR(40)  NOT NULL,
    subject VARCHAR(100) NULL,
    detail  VARCHAR(500) NULL,
    at      DATETIME(6)  NOT NULL
);

CREATE INDEX ix_audit_entry_at ON audit_entry (at);
