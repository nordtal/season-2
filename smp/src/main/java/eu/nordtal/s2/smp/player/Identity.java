package eu.nordtal.s2.smp.player;

import java.util.Locale;

/**
 * Everything the six-element player composition is drawn from, read once at join and kept in memory.
 *
 * <p>Six sources, four tables: the language and the two flags come from {@code discord_user}, the
 * aura from {@code smp_player}, the play time from {@code player_playtime} (which the proxy owns),
 * and the name from the server.
 *
 * <p>Held rather than queried: it is rendered on every tab-list refresh, chat line and nametag
 * update, so a round trip per render would be a main-thread query.
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

    /**
     * The admin flag as the roster watcher just read it.
     *
     * <p>The one field here that can be taken away mid-session: an admin role revoked in Discord
     * reaches this server within a poll interval (see {@code AdminWatch}), and the admin tag is
     * drawn on a nametag every other player can see.</p>
     */
    public Identity withAdmin(final boolean nowAdmin) {
        return new Identity(locale, nowAdmin, donor, aura, playtimeSeconds);
    }
}
