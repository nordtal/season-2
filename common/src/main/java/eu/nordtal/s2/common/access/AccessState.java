package eu.nordtal.s2.common.access;

import eu.nordtal.s2.common.SeasonPhase;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the login path needs to know about one Minecraft account <b>and about the network it
 * is trying to join</b>, answered by a single query.
 * <p>
 * The proxy asks three questions in order - linked? member and not banned? access active? - and
 * each one has its own disconnect screen. They are fields of one record rather than separate calls
 * because a login must not cost several round trips to PostgreSQL.
 * </p>
 * <p>
 * <b>{@link #phase()} rides along for the same reason, since 2026-08-31.</b>
 * {@code docs/season-phases.md} requires that "one database round trip on the login path carries
 * both the access state and the phase", and until this field existed the proxy made a second call
 * to {@code PhaseDirectory#currentPhase()} right next to this one. The phase is not a property of
 * the account, which is exactly why it is documented here as what it is: the phase the row said the
 * network was in <em>at the instant this state was read</em>. Nothing caches it as truth; the next
 * login reads it again.
 * </p>
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
 *                         Discord admin role; this is what {@code MAINTENANCE} and the proxy's
 *                         emergency {@code /phase} command are authorised by
 * @param locale           the player's language, English when unknown - never {@code null}
 * @param phase            the season phase the {@code season_phase} row carried when this state was
 *                         read; {@link SeasonPhase#MAINTENANCE} when it could not be read at all,
 *                         because the state that lets nobody in is the safe one to guess
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
        SeasonPhase phase) {

    /**
     * A {@code null} phase becomes {@link SeasonPhase#MAINTENANCE} rather than staying {@code null}.
     * {@link #mayJoin()} switches on this field, so a null would be a {@code NullPointerException}
     * on the login path; and of the four values, {@code MAINTENANCE} is the only one it is safe to
     * guess, because it is the one that lets nobody in.
     */
    public AccessState {
        if (phase == null) {
            phase = SeasonPhase.MAINTENANCE;
        }
    }

    /**
     * The answer for a UUID that has never been linked: no Discord account, no membership, no
     * access, not an admin, English - in a network whose phase could not be read either.
     * <p>
     * This is the "nothing at all came back" answer, so it pairs an unlinked account with
     * {@link SeasonPhase#MAINTENANCE}. The real login query cannot produce it any more - it always
     * returns exactly one row, with the phase in it - so this survives as the defensive fallback
     * and as a fixture for tests.
     * </p>
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
                Locale.ENGLISH, phase);
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
     * <p>
     * <b>This is phase-aware as of 2026-08-31</b>, which closed finding 1 in
     * {@code docs/state-of-play.md}. It used to require active access from every linked member
     * unconditionally, i.e. it behaved as if the network were permanently in
     * {@link SeasonPhase#SMP}. {@code docs/season-phases.md}'s phase table is what it now encodes,
     * exactly:
     * </p>
     * <table>
     *   <caption>Who gets in, per phase</caption>
     *   <tr><th>phase</th><th>who gets in</th></tr>
     *   <tr><td>{@code PRE_EVENT}</td><td>linked Discord member, not banned</td></tr>
     *   <tr><td>{@code START_EVENT}</td><td>linked Discord member, not banned</td></tr>
     *   <tr><td>{@code SMP}</td><td>the above <b>plus active access</b></td></tr>
     *   <tr><td>{@code MAINTENANCE}</td><td>admins only</td></tr>
     * </table>
     * <p>
     * The admin check in {@code MAINTENANCE} still goes through {@link #linkedMember()} first: the
     * flag is a column on the linked {@code discord_user} row, so an unlinked account can never
     * carry it, and a banned admin is still banned.
     * </p>
     * <p>
     * <b>What this method deliberately does not do is pick the disconnect screen.</b> "Refused
     * because unlinked", "refused because banned", "refused because no access" and "refused because
     * maintenance" are four different messages, and {@code network-control}'s {@code LoginGate}
     * walks the same table itself to choose between them. This method is the single-boolean form,
     * for callers - the fallback cache and the mid-session expiry sweep - that only need the answer.
     * </p>
     *
     * @return whether this account may join right now, in the phase this state was read in
     */
    public boolean mayJoin() {
        if (!linkedMember()) {
            return false;
        }
        return switch (phase) {
            case PRE_EVENT, START_EVENT -> true;
            case SMP -> accessActive;
            case MAINTENANCE -> admin;
        };
    }
}
