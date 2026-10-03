-- An override keeps the packaged texts it replaced, so when a release changes them Steward can show the old original
-- beside the new one and the override, which every process then sets aside until an admin takes it over again.
ALTER TABLE message_override
    ADD COLUMN original jsonb
        CONSTRAINT message_override_original_check CHECK (jsonb_typeof(original) = 'array');
COMMENT ON COLUMN message_override.original IS 'The packaged texts the override replaced, in order, whose SHA-256 is replaced; NULL where the language had none, and where a row was written before this column. The same on every variant of a key and language.';
