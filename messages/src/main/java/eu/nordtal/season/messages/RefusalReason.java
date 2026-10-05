package eu.nordtal.season.messages;

/** Why a request can be refused; each area declares its reasons as an enum that implements this. */
public interface RefusalReason {

    /** Returns the reason's constant name, which a caller branches on and a log or an API answer names. */
    String name();
}
