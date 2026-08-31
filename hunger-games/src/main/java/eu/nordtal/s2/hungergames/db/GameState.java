package eu.nordtal.s2.hungergames.db;

/** {@code hg_game.state}, mirrored from V5__hunger_games.sql's CHECK constraint. */
public enum GameState {
    REGISTRATION,
    COUNTDOWN,
    RUNNING,
    DECIDED
}
