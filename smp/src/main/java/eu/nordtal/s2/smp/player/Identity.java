package eu.nordtal.s2.smp.player;

import java.util.Locale;

/**
 * Everything the six-element player composition is drawn from, read once at join and kept in memory.
 *
 * <p>Six sources, four tables: the language and the two flags come from {@code discord_user}, the
 * aura from {@code smp_player}, the play time from {@code player_playtime} (which the proxy owns),
 * and the name from the server. docs/smp.md#what-a-player-looks-like is the composition itself.
 *
 * <p>Held rather than queried because it is rendered on every tab-list refresh, every chat line and
 * every nametag update. A round trip per render would be the main-thread mistake this repository
 * already made twice.
 *
 * @param locale        the wearer's language - shown as their flag, not the reader's
 * @param admin         the Discord admin role, mirrored into the database by the bot
 * @param donor         the permanent donor role
 * @param aura          current aura; the one field that changes constantly
 * @param playtimeSeconds network-wide play time, which the prestige crest is derived from
 */
public record Identity(Locale locale, boolean admin, boolean donor, int aura, long playtimeSeconds) {

    /** What somebody looks like before their row has been read, or when there is no row. */
    public static Identity unknown(final Locale locale) {
        return new Identity(locale, false, false, 0, 0L);
    }

    public Identity withAura(final int newAura) {
        return new Identity(locale, admin, donor, newAura, playtimeSeconds);
    }
}
