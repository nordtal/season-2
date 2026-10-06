package eu.nordtal.season.hungergames.game;

/** {@code hg_game.state}: a game starts in its countdown and ends decided, or aborted by a restart. */
public enum HgGameState {
    COUNTDOWN,
    RUNNING,
    DECIDED,
    ABORTED
}
