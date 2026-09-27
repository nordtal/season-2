package eu.nordtal.s2.steward.ui;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code requested_by}, picked apart into what the frontend's {@code PersonIdentity} needs.
 *
 * A Discord id to resolve through the roster, plain text when there is an actor but no id to
 * resolve it by, or a flag saying Steward itself is the one credited - a row with no requester, or
 * one prefixed {@code steward-worker}, is the nightly clock or an unattended sweep, never a person.
 */
record ActorFields(String discordId, String label, boolean system) {

    private static final Pattern TRAILING_SNOWFLAKE = Pattern.compile("^.*\\((\\d{17,20})\\)\\s*$");

    static ActorFields of(final @Nullable String requestedBy) {
        final boolean system = requestedBy == null || requestedBy.startsWith("steward-worker");
        if (system) {
            return new ActorFields("", "", true);
        }
        final Matcher match = TRAILING_SNOWFLAKE.matcher(requestedBy);
        if (match.matches()) {
            return new ActorFields(match.group(1), "", false);
        }
        return new ActorFields("", Objects.requireNonNull(requestedBy), false);
    }
}
