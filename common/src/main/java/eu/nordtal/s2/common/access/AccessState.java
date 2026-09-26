package eu.nordtal.s2.common.access;

import eu.nordtal.s2.common.SeasonPhase;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything the login path needs about one Minecraft account and the network, from a single query.
 *
 * @param minecraftAccount the UUID that was asked about, even when nothing is linked to it
 * @param discordId        the linked Discord account, {@code null} when the UUID is not linked
 * @param memberState      guild membership of that Discord account, {@code null} when unlinked
 * @param accessActive     whether a non-revoked grant covers this instant
 * @param accessValidUntil the end of the whole current run of access, {@code null} when there is none
 * @param donor            whether the linked account has the permanent donor flag
 * @param admin            whether the linked account carries the admin flag mirrored from Discord
 * @param locale           the player's language, English when unknown
 * @param phase            the season phase when this was read, {@link SeasonPhase#MAINTENANCE} if unreadable
 * @param launch           when the network opens, {@code null} when no date has been announced
 */
public record AccessState(
        UUID minecraftAccount,
        @Nullable String discordId,
        @Nullable MemberState memberState,
        boolean accessActive,
        @Nullable Instant accessValidUntil,
        boolean donor,
        boolean admin,
        Locale locale,
        SeasonPhase phase,
        @Nullable Instant launch) {

    /** Maps a {@code null} phase to {@link SeasonPhase#MAINTENANCE}, the phase that parks players harmlessly. */
    public AccessState {
        if (phase == null) {
            phase = SeasonPhase.MAINTENANCE;
        }
    }

    /** Returns an unlinked state in {@link SeasonPhase#MAINTENANCE}, a defensive fallback and test fixture. */
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
        return new AccessState(minecraftAccount, null, null, false, null, false, false, Locale.ENGLISH, phase, null);
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
     * Returns whether a bought access period still has time on it, even if it has not started yet.
     *
     * Differs from {@link #accessActive()}: a period bought before the season opens is waiting, not running.
     */
    public boolean accessBought() {
        return accessValidUntil != null;
    }

    /** Returns whether the account is linked to a non-banned member, the part that holds in every phase. */
    public boolean linkedMember() {
        return linked() && memberState == MemberState.MEMBER;
    }

    /**
     * The whole login decision in one place, so no caller re-derives it.
     *
     * Who gets in, per phase: {@code PRE_LAUNCH} is <b>admins only</b> - the network has not opened
     * yet. {@code PRE_EVENT} and {@code START_EVENT} both admit any linked, non-banned Discord
     * member. {@code SMP} admits the same <b>plus active access</b> - or the admin flag.
     * {@code MAINTENANCE} admits the same linked, non-banned member - see below.
     *
     * During {@code MAINTENANCE} admission is unchanged and the <em>destination</em>
     * ({@code limbo}) is what differs. {@link #admin()} matters in two phases: in
     * {@code PRE_LAUNCH} it <em>is</em> the admission rule, and in {@code SMP} it stands in for an
     * access period so that the admin who switches the network into {@code SMP} is not disconnected
     * by their own switch. A banned admin is still banned, because {@link #linkedMember()} is asked
     * first.
     *
     * This deliberately does not pick the disconnect screen - unlinked, banned and no-access are
     * three different messages, and {@code proxy}'s {@code LoginGate} chooses between
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
