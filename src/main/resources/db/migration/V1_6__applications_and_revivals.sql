-- A revival pays for several participants in one request: it has no single spexare, and is named instead
ALTER TABLE payment_request
    MODIFY spexare_id CHAR(36) NULL,
    ADD COLUMN payer_label VARCHAR(200) NULL;

-- Applications for membership from users whose account is not linked to a spexare (spec 5.6)
CREATE TABLE application
(
    pk               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id               CHAR(36)      NOT NULL,
    user_id          CHAR(36)      NOT NULL,
    applicant_name   VARCHAR(200)  NOT NULL,
    text             VARCHAR(1000) NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    decided_by       VARCHAR(36)   NULL,
    decided_at       DATETIME(6)   NULL,
    decision_message VARCHAR(500)  NULL,
    spexare_id       CHAR(36)      NULL,
    CONSTRAINT uq_application_id UNIQUE (id)
);

CREATE INDEX ix_application_user ON application (user_id, created_at);
CREATE INDEX ix_application_status ON application (status, created_at);
