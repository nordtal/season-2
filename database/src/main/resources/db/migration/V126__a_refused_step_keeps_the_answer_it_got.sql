-- A step that Docker or the process refused keeps the answer it got, so the cause can be read after the run.
ALTER TABLE run_step ADD COLUMN refusal text;
ALTER TABLE run_step ADD CONSTRAINT run_step_refusal_on_refused CHECK (refusal IS NULL OR reason IS NOT DISTINCT FROM 'REFUSED');
COMMENT ON COLUMN run_step.refusal IS 'The answer a refused step got, as Docker or the process gave it; null when it gave none.';
