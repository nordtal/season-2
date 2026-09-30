package eu.nordtal.s2.hungergames.db;

/** {@code hg_game.state}, mirrored from V1__schema.sql's CHECK constraint. */
public enum GameState {
    REGISTRATION,
    COUNTDOWN,
    RUNNING,
    DECIDED
}
