-- An aura change records its cause as typed values, each reason in a table of its own beside the event: a duel's type,
-- a death's damage type, an advancement's key, the objective a contribution paid for, the actor of an admin's change.
-- The text column that held them all, depending on the reason, goes once its values are moved.
CREATE TABLE smp_aura_duel
(
    event_id  uuid        PRIMARY KEY
        CONSTRAINT smp_aura_duel_event_id_fkey REFERENCES smp_aura_event (id) ON DELETE CASCADE,
    duel_type varchar(16) NOT NULL CONSTRAINT smp_aura_duel_duel_type_check CHECK (duel_type IN ('SWORD', 'BOW'))
);
COMMENT ON TABLE smp_aura_duel IS 'Owned by smp: the duel an aura change of DUEL_WIN or DUEL_LOSS was paid for.';

CREATE TABLE smp_aura_death
(
    event_id    uuid PRIMARY KEY
        CONSTRAINT smp_aura_death_event_id_fkey REFERENCES smp_aura_event (id) ON DELETE CASCADE,
    damage_type text NOT NULL CONSTRAINT smp_aura_death_damage_type_namespaced CHECK (damage_type ~ '^[a-z0-9_.-]+:')
);
COMMENT ON TABLE smp_aura_death IS 'Owned by smp: the damage type of a death that cost aura; a death without one has no row.';

CREATE TABLE smp_aura_advancement
(
    event_id    uuid PRIMARY KEY
        CONSTRAINT smp_aura_advancement_event_id_fkey REFERENCES smp_aura_event (id) ON DELETE CASCADE,
    advancement text NOT NULL CONSTRAINT smp_aura_advancement_advancement_namespaced CHECK (advancement ~ '^[a-z0-9_.-]+:')
);
COMMENT ON TABLE smp_aura_advancement IS 'Owned by smp: the advancement an aura change of ADVANCEMENT was paid for.';

CREATE TABLE smp_aura_contribution
(
    event_id     uuid PRIMARY KEY
        CONSTRAINT smp_aura_contribution_event_id_fkey REFERENCES smp_aura_event (id) ON DELETE CASCADE,
    objective_id uuid NOT NULL
        CONSTRAINT smp_aura_contribution_objective_id_fkey REFERENCES smp_objective (id) ON DELETE CASCADE
);
COMMENT ON TABLE smp_aura_contribution IS 'Owned by smp: the objective an aura change of CONTRIBUTION was its share of.';
CREATE INDEX smp_aura_contribution_objective_id_idx ON smp_aura_contribution (objective_id);

CREATE TABLE smp_aura_admin
(
    event_id   uuid        PRIMARY KEY
        CONSTRAINT smp_aura_admin_event_id_fkey REFERENCES smp_aura_event (id) ON DELETE CASCADE,
    actor_kind varchar(16) NOT NULL
        CONSTRAINT smp_aura_admin_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST', 'BOT')),
    actor_id   varchar(32),
    CONSTRAINT smp_aura_admin_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
COMMENT ON TABLE smp_aura_admin IS 'Owned by smp: who booked an aura change of ADMIN by hand.';

GRANT SELECT, INSERT, UPDATE, DELETE ON smp_aura_duel, smp_aura_death, smp_aura_advancement, smp_aura_contribution,
    smp_aura_admin TO ${role_smp};

INSERT INTO smp_aura_duel (event_id, duel_type)
SELECT id, ref FROM smp_aura_event WHERE reason IN ('DUEL_WIN', 'DUEL_LOSS') AND ref IN ('SWORD', 'BOW');

-- A damage type written without its namespace is a vanilla one.
INSERT INTO smp_aura_death (event_id, damage_type)
SELECT id, CASE WHEN ref LIKE '%:%' THEN ref ELSE 'minecraft:' || ref END
FROM smp_aura_event WHERE reason IN ('DEATH', 'DEATH_LISTED') AND ref IS NOT NULL;

INSERT INTO smp_aura_advancement (event_id, advancement)
SELECT id, ref FROM smp_aura_event WHERE reason = 'ADVANCEMENT' AND ref LIKE '%:%';

-- A contribution was written as its milestone and objective key.
INSERT INTO smp_aura_contribution (event_id, objective_id)
SELECT event.id, objective.id
FROM smp_aura_event event
         JOIN smp_objective objective ON event.ref = objective.milestone_key || '/' || objective.key
WHERE event.reason = 'CONTRIBUTION';

-- The console was the only place an admin changed aura from.
INSERT INTO smp_aura_admin (event_id, actor_kind)
SELECT id, 'HOST' FROM smp_aura_event WHERE reason = 'ADMIN';

ALTER TABLE smp_aura_event DROP COLUMN ref;
