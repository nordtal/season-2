package eu.nordtal.s2.database.access;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.common.language.Locales;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Who a Minecraft account is on this network, as a server holds it for a session.
 *
 * @param discordId the linked Discord account, or {@code null} for an account nobody linked
 * @param playtimeSeconds network-wide play time, which a prestige crest is derived from
 */
public record PlayerIdentity(
        PlayerId player,
        @Nullable DiscordId discordId,
        Locale locale,
        boolean admin,
        boolean donor,
        long playtimeSeconds) {

    public PlayerIdentity {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(locale, "locale");
    }

    /** Returns what an account looks like before its row is read, or when it has none. */
    public static PlayerIdentity unknown(final PlayerId player) {
        return new PlayerIdentity(player, null, Locales.DEFAULT, false, false, 0L);
    }

    /** Returns the same identity with the admin flag as the roster now has it. */
    public PlayerIdentity withAdmin(final boolean nowAdmin) {
        return new PlayerIdentity(player, discordId, locale, nowAdmin, donor, playtimeSeconds);
    }
}
