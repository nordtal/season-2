-- steward keeps when this session's step-up ends, chosen per step-up, instead of counting a fixed window from verified_at.
ALTER TABLE steward_session ADD COLUMN step_up_until timestamptz;

COMMENT ON COLUMN steward_session.step_up_until IS
    'When the step-up of the last ceremony ends; NULL once ended early. verified_at stays as the door.';
