package eu.nordtal.s2.steward;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.DatabaseWaiting;
import eu.nordtal.s2.steward.auth.Credentials;
import eu.nordtal.s2.steward.auth.Sessions;
import eu.nordtal.s2.steward.data.Data;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The host command that clears one account's second factor. */
final class ForgetFactors {

    private ForgetFactors() {}

    /**
     * {@code forget-factors <discord-id>}: the way back in after a lost authenticator, run from a host shell.
     *
     * Removes keys and sessions together, so no signed-in browser stays inside; returns the exit status.
     */
    static int run(final String[] args, final @Nullable DatabaseSpec databaseConfig, final Clock clock) {
        if (args.length != 2 || args[1].isBlank()) {
            System.err.println("Usage: forget-factors <discord-id>");
            System.err.println("Clears the security keys and the sessions of one account, so that"
                    + " its next sign-in starts at the setup page.");
            return 2;
        }
        final DiscordId discordId = DiscordId.of(args[1].trim());
        // Anything but digits would run a DELETE matching nothing.
        if (!discordId.value().chars().allMatch(Character::isDigit)) {
            System.err.println("`" + discordId + "` is not a Discord id - those are digits only."
                    + " Take it from the journal or from the account list.");
            return 2;
        }
        if (databaseConfig == null) {
            return 1;
        }
        final Database opened =
                DatabaseWaiting.openDatabase(databaseConfig, "steward-forget-factors", Waiting.on(clock));
        if (opened == null) {
            return 1;
        }
        try (Database database = opened) {
            final Data data = new Data(database, clock);
            final int keys = new Credentials(data.dataSource()).forget(discordId);
            final int signedOut = new Sessions(data.dataSource(), Duration.ofDays(1)).endAllOf(discordId);
            if (keys == 0 && signedOut == 0) {
                System.out.println("Nothing to forget: " + discordId + " has no security key and"
                        + " no session. Its next sign-in already starts at the setup page.");
                return 0;
            }
            // Written after the deletes, so a row never claims something that did not happen.
            data.audit()
                    .record(AuditLine.about(
                            "FORGET_FACTORS", Actor.HOST, discordId, Map.of("keys", keys, "sessions", signedOut)));
            System.out.println(
                    "Cleared " + keys + " security key(s) and " + signedOut + " session(s) of " + discordId + ".");
            System.out.println("Its next sign-in will ask for Discord and then register a new key.");
            return 0;
        }
    }
}
