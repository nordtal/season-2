package eu.nordtal.season.database.registration;

/** The {@code registration.state} of one registration round of a game. */
public enum RegistrationState {
    OPEN,

    /** A game of it is under way. Its teams do not change until the game is aborted or decided. */
    CLOSED,

    /** Its game was decided. The next registration opens a new round. */
    ENDED
}
