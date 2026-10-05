package eu.nordtal.season.discordbot.registration;

import eu.nordtal.season.common.id.DiscordId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of registering a team, from {@link Teams#register(DiscordId, String)}.
 *
 * @param teamId null unless {@link #status()} is {@link Status#REGISTERED}
 */
public record RegistrationResult(Status status, @Nullable UUID teamId) {

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

    /** The round is closed while a game of it is under way. */
    public static RegistrationResult closed() {
        return new RegistrationResult(Status.CLOSED, null);
    }

    public enum Status {
        REGISTERED,
        CLOSED,
        /** Outside the 3 to 15 character range the modal already enforces. */
        INVALID_NAME,
        NAME_TAKEN,
        /** Already OWNER, INVITED or ACCEPTED on some team in the current round. */
        ALREADY_REGISTERED
    }
}
