-- The packaged texts an override replaced are a row each, once per variant, where every variant row of the override
-- carried all of them.
CREATE TABLE message_override_original
(
    bundle   varchar(32)  NOT NULL,
    key      varchar(128) NOT NULL,
    language varchar(16)  NOT NULL,
    variant  smallint     NOT NULL CONSTRAINT message_override_original_variant_check CHECK (variant >= 0),
    text     text         NOT NULL,
    CONSTRAINT message_override_original_pkey PRIMARY KEY (bundle, key, language, variant)
);
COMMENT ON TABLE message_override_original IS 'Owned by steward-ui: one packaged text an override of the same bundle, key and language replaced, whose SHA-256 over all variants is message_override.replaced. steward shows it beside a text a release changed.';
GRANT SELECT ON message_override_original TO ${role_read};
GRANT INSERT, UPDATE, DELETE ON message_override_original TO ${role_steward_ui};

INSERT INTO message_override_original (bundle, key, language, variant, text)
SELECT bundle, key, language, (original.ordinality - 1)::smallint, original.text
FROM message_override,
     jsonb_array_elements_text(message_override.original) WITH ORDINALITY AS original (text, ordinality)
WHERE variant = 0 AND original IS NOT NULL;

ALTER TABLE message_override DROP COLUMN original;
