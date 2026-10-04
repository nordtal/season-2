package eu.nordtal.s2.database.registration;

/** {@code registration.state}: where one round of registration for a game stands. */
public enum RegistrationState {

    /** Teams register and invite. */
    OPEN,

    /** A game of it is under way; nothing about its teams changes until that game is aborted or decided. */
    CLOSED,

    /** Its game was decided; the next registration opens a new round. */
    ENDED
}
