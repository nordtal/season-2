-- A journal line keeps the payment reference it names and the days it grants or books as fields of their own, beside
-- the message that renders them, so the journal filters by them. A line already written takes them from the values of
-- those names, and only of their kind.
ALTER TABLE audit_log
    ADD COLUMN reference text,
    ADD COLUMN days      integer;

UPDATE audit_log
SET reference = CASE WHEN line #>> '{args,reference,kind}' = 'text' THEN line #>> '{args,reference,value}' END,
    days      = CASE WHEN line #>> '{args,days,kind}' = 'number' THEN (line #>> '{args,days,value}')::integer END
WHERE line -> 'args' ?| ARRAY ['reference', 'days'];

CREATE INDEX audit_log_reference_idx ON audit_log (reference) WHERE reference IS NOT NULL;
COMMENT ON COLUMN audit_log.reference IS 'The payment reference the line names, the value of its message''s reference.';
COMMENT ON COLUMN audit_log.days IS 'The days the line grants or books, the value of its message''s days.';
