-- Three kinds of fee: standard (one person, a year), family (one pays, every family member gets the membership, a year) and
-- life (once). Admins set the price of each, for each year.
ALTER TABLE fee_level
    ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'STANDARD';
UPDATE fee_level SET kind = 'LIFETIME' WHERE lifetime;

-- A family membership: the memberships of everyone in the family share a family id; the one who paid is the request's spexare
ALTER TABLE membership
    ADD COLUMN family_id CHAR(36) NULL;

ALTER TABLE payment_request
    ADD COLUMN family_id CHAR(36) NULL,
    ADD COLUMN family_note VARCHAR(500) NULL;
