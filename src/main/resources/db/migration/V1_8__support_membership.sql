-- A support membership (stödmedlemskap) gives other access than the full kinds do, so each kind of fee has its own default access
ALTER TABLE default_access
    ADD COLUMN profile VARCHAR(10) NOT NULL DEFAULT 'MEMBER',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (profile, client_id);

ALTER TABLE membership
    ADD COLUMN support BOOLEAN NOT NULL DEFAULT FALSE;
