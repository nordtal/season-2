CREATE TABLE smp_duel_stake
(
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    duel       uuid        NOT NULL,
    discord_id varchar(32) NOT NULL REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    aura       integer     NOT NULL DEFAULT 0 CHECK (aura >= 0),
    item       text,
    amount     integer CHECK (amount > 0),
    data       text,
    created    timestamptz NOT NULL DEFAULT now(),
    CHECK ((item IS NULL) = (amount IS NULL) AND (item IS NULL) = (data IS NULL))
);
CREATE INDEX smp_duel_stake_duel ON smp_duel_stake (duel);
COMMENT ON TABLE smp_duel_stake IS 'Owned by smp: the aura and items two fighters staked, held from the lock until the duel pays out or refunds.';
GRANT SELECT, INSERT, DELETE ON smp_duel_stake TO ${role_smp};

ALTER TABLE smp_pending_item
    ADD COLUMN returned_stake boolean NOT NULL DEFAULT false;
