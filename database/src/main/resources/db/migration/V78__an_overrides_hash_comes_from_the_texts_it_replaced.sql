-- Every process computes an override's hash from message_override_original since 0.50.0, so the copy on each variant row goes.
ALTER TABLE message_override DROP COLUMN replaced;

COMMENT ON TABLE message_override_original IS 'Owned by steward-ui: one packaged text an override of the same bundle, key and language replaced. Every process compares their SHA-256 over all variants with its jar; steward shows them beside a text a release changed.';
