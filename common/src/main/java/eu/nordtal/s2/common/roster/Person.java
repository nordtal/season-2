package eu.nordtal.s2.common.roster;

import java.time.Instant;
import java.util.UUID;

/**
 * One person as a list shows them: their {@code discord_user} row, the account link if there is
 * one, and the two numbers about access that a reader would otherwise have to work out themselves.
 *
 * <h2>Why the access columns are on this record and not fetched per row</h2>
 * Because a roster of a few hundred people asking one more question per person is a few hundred
 * round trips for a page nobody scrolls to the bottom of. {@link RosterDirectory#people(int)} is
 * one statement with two {@code LEFT JOIN}s, and these two components are what that costs.
 *
 * <h2>{@code accessUntil} counts revoked grants; {@code accessActive} does not</h2>
 * They answer different questions and are deliberately not two views of one predicate.
 * {@code accessActive} is the login decision - a revoked grant never counts, not even inside its
 * own window - and it is the same predicate the proxy evaluates in
 * {@code AccessDirectory#accessState}. {@code accessUntil} is "when does the latest period on
 * record end", revoked or not, so that somebody whose access was taken away is distinguishable in
 * a list from somebody who never bought any: the first shows a date with an inactive flag beside
 * it, the second shows nothing at all. Treating a revoked period as if it had never been written
 * would throw that difference away, and the difference is exactly what an admin looking at this
 * list is looking for.
 *
 * <p>Every component is a JDK type: this record is serialised straight to JSON.
 *
 * @param discordId     the Discord snowflake, the primary key of {@code discord_user}
 * @param memberState   guild membership as the bot last saw it - {@code MEMBER}, {@code LEFT} or
 *                      {@code BANNED}. Text rather than the {@code MemberState} enum, because an
 *                      unknown value written by hand must be shown, not throw on the way out
 * @param donor         the permanent donor flag
 * @param admin         whether this account is an admin right now, in the admin tree
 * @param locale        the IETF language tag, {@code en} or {@code de} today
 * @param updated       when the row last changed - what {@link RosterDirectory#people(int)} orders by
 * @param minecraftUuid the linked Minecraft account, {@code null} when there is no
 *                      {@code account_link} row
 * @param linked        when that link was written, {@code null} for the same reason
 * @param accessUntil   the latest {@code valid_until} of any grant this person has, revoked ones
 *                      included; {@code null} when there has never been a grant
 * @param accessActive  whether a non-revoked grant covers this instant
 * @param discordUsername           the global Discord username last observed, see
 *                                   {@code eu.nordtal.s2.common.access.DiscordProfile}
 * @param discordUsernameUpdated    when that was last written, {@code null} together with it
 * @param discordDisplayName        the guild nickname last observed, {@code null} when unset or
 *                                   when the account is no longer a member
 * @param discordDisplayNameUpdated when that was last written, {@code null} together with it
 * @param discordAvatarUrl          the guild avatar last observed, {@code null} for the same
 *                                   reasons as {@code discordDisplayName}
 * @param discordAvatarUrlUpdated   when that was last written, {@code null} together with it
 * @param mcName                    the Minecraft name last seen at login, see
 *                                   {@code eu.nordtal.s2.common.access.MinecraftProfile};
 *                                   {@code null} when unlinked or never seen
 * @param mcNameUpdated             when that was last written, {@code null} together with it
 * @param playtimeSeconds           total online time across the network, from
 *                                   {@code player_playtime}; {@code null} - not zero - when there
 *                                   is no row, i.e. when this account has never been online. The
 *                                   prestige tier is derived from this number and stored nowhere,
 *                                   which is why a list of people prints it at all (steward/119)
 * @param adminGrantedBy            the admin who granted this one, {@code null} for the root and
 *                                   for everybody who is not an admin
 * @param adminGrantedAt            when this account became an admin, {@code null} unless it is one
 */
public record Person(
        String discordId,
        String memberState,
        boolean donor,
        boolean admin,
        String locale,
        Instant updated,
        UUID minecraftUuid,
        Instant linked,
        Instant accessUntil,
        boolean accessActive,
        String discordUsername,
        Instant discordUsernameUpdated,
        String discordDisplayName,
        Instant discordDisplayNameUpdated,
        String discordAvatarUrl,
        Instant discordAvatarUrlUpdated,
        String mcName,
        Instant mcNameUpdated,
        Long playtimeSeconds,
        String adminGrantedBy,
        Instant adminGrantedAt) {}
