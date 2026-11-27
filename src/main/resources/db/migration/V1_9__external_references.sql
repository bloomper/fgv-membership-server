-- Payments told by other applications: which application told, and that application's own id for the payment. The same id
-- from the same application is the same payment; an application that sends none gets no protection against telling twice.
ALTER TABLE external_payment
    ADD COLUMN source VARCHAR(100) NULL,
    MODIFY external_ref VARCHAR(100) NULL,
    DROP INDEX uq_external_payment_ref,
    ADD CONSTRAINT uq_external_payment_source_ref UNIQUE (source, external_ref);

UPDATE external_payment SET source = 'fgv-external-payment-service' WHERE source IS NULL;

-- The treasurer and the bookkeeping see which application the payment came from, and its id for it
ALTER TABLE payment_request
    ADD COLUMN external_source VARCHAR(100) NULL,
    ADD COLUMN external_reference VARCHAR(100) NULL;
