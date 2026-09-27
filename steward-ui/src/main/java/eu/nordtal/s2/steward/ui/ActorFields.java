package eu.nordtal.s2.steward.ui;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code requested_by}, split into what the frontend's {@code PersonIdentity} needs.
 *
 * No requester, or one prefixed {@code steward-worker}, is Steward itself; otherwise a Discord id or plain text.
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
