-- The import API (fgv-membership-spec 5.9; spexregister-spec, docs/migration.md, 4): what each source key made, so that
-- spexregister-migration can be run again without making a membership twice, and leaves alone what the board changed.
CREATE TABLE import_key
(
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_key     VARCHAR(1000) NOT NULL,
    -- SHA-256 of the source key, for the unique index
    source_hash    CHAR(64)      NOT NULL,
    record_kind    VARCHAR(30)   NOT NULL,
    record_id      CHAR(36)      NOT NULL,
    -- SHA-256 of the record as last imported, to tell UNCHANGED from UPDATED
    imported_hash  CHAR(64)      NOT NULL,
    -- A fingerprint of the membership right after the import; a different one later means the board changed it (KEPT)
    record_version BIGINT        NOT NULL,
    imported_by    VARCHAR(64)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT uq_import_key_source UNIQUE (source_hash),
    INDEX ix_import_key_prefix (source_key(255))
);
