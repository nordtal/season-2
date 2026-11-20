CREATE TABLE smp_pending_item
(
    discord_id varchar(32) NOT NULL REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    id         bigint      GENERATED ALWAYS AS IDENTITY,
    item       text        NOT NULL,
    amount     integer     NOT NULL CHECK (amount > 0),
    data       text        NOT NULL,
    PRIMARY KEY (discord_id, id)
);
COMMENT ON TABLE smp_pending_item IS 'Owned by smp: items to give when a player joins.';
GRANT SELECT, INSERT, DELETE ON smp_pending_item TO ${role_smp};
