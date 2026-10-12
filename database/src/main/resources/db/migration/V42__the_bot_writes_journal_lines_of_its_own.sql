-- The bot writes a journal line of its own for what it tells the admins unasked, filed under itself as the actor.
ALTER TABLE audit_log
    DROP CONSTRAINT audit_log_actor_kind_check,
    ADD CONSTRAINT audit_log_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST', 'BOT'));
