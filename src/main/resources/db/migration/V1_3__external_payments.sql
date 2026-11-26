-- The roles a user created from a payment made elsewhere (the WordPress shop) gets, per application. Admins keep them.
CREATE TABLE default_access
(
    client_id VARCHAR(100) NOT NULL PRIMARY KEY,
    role      VARCHAR(50)  NOT NULL
);

-- Settings that are set once by the application itself, e.g. that the defaults above have been seeded
CREATE TABLE app_setting
(
    name  VARCHAR(100) NOT NULL PRIMARY KEY,
    value VARCHAR(500) NOT NULL
);

-- Payments told by another system, once each: the system's own reference is what makes a retry harmless
CREATE TABLE external_payment
(
    pk              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_user     VARCHAR(36)  NOT NULL,
    external_ref    VARCHAR(100) NOT NULL,
    request_id      CHAR(36)     NULL,
    status          VARCHAR(20)  NOT NULL,
    account_created BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uq_external_payment_ref UNIQUE (external_ref)
);
