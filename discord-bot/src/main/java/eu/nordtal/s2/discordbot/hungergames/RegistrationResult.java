package eu.nordtal.s2.discordbot.hungergames;

import java.util.UUID;

/**
 * The outcome of registering a team, from {@link Teams#register(String, String)}.
 * <p>
 * A record rather than an exception, the same reasoning as {@code LinkRedemption} in
 * {@code :common}: a taken name or a second registration attempt are ordinary outcomes of a modal
 * submission, not failures worth a stack trace.
 * </p>
 */
public record RegistrationResult(Status status, UUID teamId) {

    public static RegistrationResult registered(final UUID teamId) {
        return new RegistrationResult(Status.REGISTERED, teamId);
    }

    public static RegistrationResult invalidName() {
        return new RegistrationResult(Status.INVALID_NAME, null);
    }

    public static RegistrationResult nameTaken() {
        return new RegistrationResult(Status.NAME_TAKEN, null);
    }

    public static RegistrationResult alreadyRegistered() {
        return new RegistrationResult(Status.ALREADY_REGISTERED, null);
    }

    public enum Status {
        REGISTERED,
        /** Outside the 3-15 character range the modal already enforces - a second guard, not the first. */
        INVALID_NAME,
        NAME_TAKEN,
        /** Already OWNER, INVITED or ACCEPTED on some team in the open game. */
        ALREADY_REGISTERED
    }
}
