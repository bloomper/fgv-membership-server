-- Registrations: the link in the verification email, and the message to the administrators.
-- Names and addresses live in Keycloak, not here.
CREATE TABLE registration
(
    pk               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id               CHAR(36)     NOT NULL,
    keycloak_user_id CHAR(36)     NOT NULL,
    token_hash       CHAR(64)     NOT NULL,
    expires_at       DATETIME(6)  NOT NULL,
    verified_at      DATETIME(6)  NULL,
    message          VARCHAR(500) NULL,
    created_at       DATETIME(6)  NOT NULL,
    CONSTRAINT uq_registration_id UNIQUE (id),
    CONSTRAINT uq_registration_token UNIQUE (token_hash)
);

CREATE INDEX ix_registration_user ON registration (keycloak_user_id, created_at);
CREATE INDEX ix_registration_created ON registration (created_at);

-- The audit of user administration
CREATE TABLE access_change
(
    pk           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id           CHAR(36)     NOT NULL,
    user_id      CHAR(36)     NOT NULL,
    changed_by   CHAR(36)     NOT NULL,
    action       VARCHAR(20)  NOT NULL,
    client_id    VARCHAR(100) NULL,
    roles_before VARCHAR(500) NULL,
    roles_after  VARCHAR(500) NULL,
    at           DATETIME(6)  NOT NULL,
    CONSTRAINT uq_access_change_id UNIQUE (id)
);

CREATE INDEX ix_access_change_user ON access_change (user_id, at);
