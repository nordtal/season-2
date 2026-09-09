package eu.nordtal.s2.common.access;

import eu.nordtal.s2.common.SeasonPhase;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the login path needs to know about one Minecraft account and about the network it is
 * trying to join, answered by a single query, because a login must not cost several round trips to
 * PostgreSQL.
 *
 * <p>{@link #phase()} is not a property of the account: it is the phase the row said the network
 * was in at the instant this state was read. Nothing caches it as truth.
 *
 * @param minecraftAccount the UUID that was asked about - always the UUID that was asked about,
 *                         even when nothing is linked to it
 * @param discordId        the linked Discord account, {@code null} when the UUID is not linked
 * @param memberState      guild membership of that Discord account, {@code null} when unlinked
 * @param accessActive     whether a non-revoked grant covers this instant
 * @param accessValidUntil the end of the current run of access, {@code null} when there is none;
 *                         this is the end of the whole appended chain, not of one grant
 * @param donor            whether the linked account has the permanent donor flag
 * @param admin            whether the linked account carries the admin flag mirrored from the
 *                         Discord admin role; it authorises the proxy's emergency {@code /phase}
 *                         command, and during {@code MAINTENANCE} it is what keeps a player off
 *                         {@code limbo}
 * @param locale           the player's language, English when unknown - never {@code null}
 * @param phase            the season phase the {@code season_phase} row carried when this state was
 *                         read; {@link SeasonPhase#MAINTENANCE} when it could not be read at all,
 *                         because the state that lets nobody in is the safe one to guess
 * @param launch           when the network opens, from the same {@code season_phase} row;
 *                         {@code null} when no date has been announced. It rides along for the same
 *                         reason {@code phase} does: the {@link SeasonPhase#PRE_LAUNCH} disconnect
 *                         screens count down to it and must not cost a second round trip
 */
public record AccessState(
        UUID minecraftAccount,
        String discordId,
        MemberState memberState,
        boolean accessActive,
        Instant accessValidUntil,
        boolean donor,
        boolean admin,
        Locale locale,
        SeasonPhase phase,
        Instant launch) {

    /**
     * A {@code null} phase becomes {@link SeasonPhase#MAINTENANCE}: {@link #mayJoin()} switches on
     * this field, so a null would be a {@code NullPointerException} on the login path.
     *
     * <p>{@code MAINTENANCE} is the safe guess because it is the phase that puts a player somewhere
     * harmless - a proxy that cannot read the phase parks them in the waiting room rather than
     * guessing them onto a game server that may not be theirs.
     */
    public AccessState {
        if (phase == null) {
            phase = SeasonPhase.MAINTENANCE;
        }
    }

    /**
     * The "nothing at all came back" answer: an unlinked account in
     * {@link SeasonPhase#MAINTENANCE}. The login query always returns a row, so this is a defensive
     * fallback and a test fixture.
     *
     * @param minecraftAccount the UUID that was asked about
     * @return an unlinked state in {@code MAINTENANCE}
     */
    public static AccessState unlinked(final UUID minecraftAccount) {
        return unlinked(minecraftAccount, SeasonPhase.MAINTENANCE);
    }

    /**
     * The answer for a UUID nobody has linked, in a network whose phase <em>is</em> known.
     *
     * @param minecraftAccount the UUID that was asked about
     * @param phase            the phase the row carried
     * @return an unlinked state
     */
    public static AccessState unlinked(final UUID minecraftAccount, final SeasonPhase phase) {
        return new AccessState(minecraftAccount, null, null, false, null, false, false,
                Locale.ENGLISH, phase, null);
    }

    /** @return whether a Discord account is linked to this UUID */
    public boolean linked() {
        return discordId != null;
    }

    /** @return the linked Discord account, if any */
    public Optional<String> discordAccount() {
        return Optional.ofNullable(discordId);
    }

    /** @return the end of the current run of access, if any */
    public Optional<Instant> validUntil() {
        return Optional.ofNullable(accessValidUntil);
    }

    /** @return when the network opens, if a date has been announced */
    public Optional<Instant> launchAt() {
        return Optional.ofNullable(launch);
    }

    /**
     * Whether any access period has been bought and still has time on it - <b>not</b> the same
     * question as {@link #accessActive()}. A period bought before the season opens is waiting
     * rather than running, and somebody who has one must not be asked to buy again.
     *
     * @return whether a period exists that has not run out
     */
    public boolean accessBought() {
        return accessValidUntil != null;
    }

    /**
     * Whether this account is linked to a Discord account that is a member and is not banned. This
     * is the part of the decision that holds in <b>every</b> phase; {@link #mayJoin()} is that plus
     * whatever the phase adds on top.
     *
     * @return whether the account clears the two phase-independent checks
     */
    public boolean linkedMember() {
        return linked() && memberState == MemberState.MEMBER;
    }

    /**
     * The whole login decision in one place, so no caller re-derives it.
     *
     * <table>
     *   <caption>Who gets in, per phase</caption>
     *   <tr><th>phase</th><th>who gets in</th></tr>
     *   <tr><td>{@code PRE_LAUNCH}</td><td><b>admins only</b> - the network has not opened yet</td></tr>
     *   <tr><td>{@code PRE_EVENT}</td><td>linked Discord member, not banned</td></tr>
     *   <tr><td>{@code START_EVENT}</td><td>linked Discord member, not banned</td></tr>
     *   <tr><td>{@code SMP}</td><td>the above <b>plus active access</b> - or the admin flag</td></tr>
     *   <tr><td>{@code MAINTENANCE}</td><td>the same linked, non-banned member - see below</td></tr>
     * </table>
     *
     * <p>During {@code MAINTENANCE} admission is unchanged and the <em>destination</em>
     * ({@code limbo}) is what differs. {@link #admin()} matters in two phases: in
     * {@code PRE_LAUNCH} it <em>is</em> the admission rule, and in {@code SMP} it stands in for an
     * access period so that the admin who switches the network into {@code SMP} is not disconnected
     * by their own switch. A banned admin is still banned, because {@link #linkedMember()} is asked
     * first.
     *
     * <p>This deliberately does not pick the disconnect screen - unlinked, banned and no-access are
     * three different messages, and {@code network-control}'s {@code LoginGate} chooses between
     * them. This is the single-boolean form for callers that only need the answer.
     *
     * @return whether this account may join right now, in the phase this state was read in
     */
    public boolean mayJoin() {
        if (!linkedMember()) {
            return false;
        }
        return switch (phase) {
            // Every phase admits the same linked, non-banned member; only SMP asks for more.
            case PRE_EVENT, START_EVENT, MAINTENANCE -> true;
            case SMP -> accessActive || admin;
            // Before the network has ever opened, an admin is the only person who may be on it.
            case PRE_LAUNCH -> admin;
        };
    }
}
