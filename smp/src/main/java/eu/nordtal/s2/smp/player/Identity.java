package eu.nordtal.s2.smp.player;

import java.util.Locale;

/**
 * Everything the six-element player composition is drawn from, as {@link Identities} holds it.
 *
 * @param locale the wearer's language, shown as their flag rather than the reader's
 * @param admin the Discord admin role, mirrored into the database by the bot
 * @param donor the permanent donor role
 * @param aura current aura; the one field that changes constantly
 * @param playtimeSeconds network-wide play time, which the prestige crest is derived from
 */
public record Identity(Locale locale, boolean admin, boolean donor, int aura, long playtimeSeconds) {

    /** What somebody looks like before their row has been read, or when there is no row. */
    public static Identity unknown(final Locale locale) {
        return new Identity(locale, false, false, 0, 0L);
    }
}
