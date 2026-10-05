package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.language.Locales;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Who a Minecraft account is on this network: the one record every process reads a player's values from.
 * A server holds it for a session and reads it again on every signal, never beside the holder.
 *
 * @param discordId       the linked Discord account, or {@code null} for an account nobody linked
 * @param name            the Minecraft name last seen at login, or {@code null} before the first one
 * @param language        the language they read, the network's default where they chose none
 * @param timeZone        the zone they read times in, or {@code null} for the network's
 * @param aura            the SMP aura, 0 for an account that has none
 * @param playtimeSeconds network-wide play time, which a prestige crest is derived from
 */
public record PlayerIdentity(
        PlayerId player,
        @Nullable DiscordId discordId,
        @Nullable String name,
        Locale language,
        @Nullable ZoneId timeZone,
        boolean admin,
        boolean donor,
        int aura,
        long playtimeSeconds) {

    public PlayerIdentity {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(language, "language");
    }

    /** Returns what an account looks like before its row is read, or when it has none. */
    public static PlayerIdentity unknown(final PlayerId player) {
        return new PlayerIdentity(player, null, null, Locales.DEFAULT, null, false, false, 0, 0L);
    }

    /** Returns the zone this player reads times in: their own, or the network's. */
    public ZoneId timeZoneOr(final ZoneId network) {
        return timeZone == null ? network : timeZone;
    }
}
