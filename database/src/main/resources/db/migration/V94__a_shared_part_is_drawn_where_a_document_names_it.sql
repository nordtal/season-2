-- A document names a shared part in place of holding a copy, and every service draws the part where it is named.
-- The shared parts saved as copies are dropped, not carried over.
DELETE FROM shared_part;
COMMENT ON TABLE shared_part IS 'Owned by steward, where an admin saves a shared part: one row per shared part. Every service reads it to draw the documents that name it.';

GRANT SELECT ON shared_part TO ${role_read};
