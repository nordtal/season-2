CREATE TABLE run_player_move
(
    step bigint NOT NULL REFERENCES run_step (id) ON DELETE CASCADE,
    player uuid NOT NULL,
    from_service text NOT NULL,
    to_service text NOT NULL,
    switching boolean NOT NULL,
    arrived boolean NOT NULL DEFAULT false,
    PRIMARY KEY (step, player)
);
CREATE INDEX run_player_move_player ON run_player_move (player, step DESC);
GRANT SELECT ON run_player_move TO ${role_read};
GRANT INSERT, UPDATE ON run_player_move TO ${role_proxy};
