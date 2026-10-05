package eu.nordtal.season.hungergames.db;

/** {@code hg_game.state}: a game starts in its countdown and ends decided, or aborted by a restart. */
public enum GameState {
    COUNTDOWN,
    RUNNING,
    DECIDED,
    ABORTED
}
