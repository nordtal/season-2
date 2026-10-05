package eu.nordtal.season.database.access;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.DiscordId;
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
 * @param packExempt       whether an admin let this account through without the resource pack
 * @param playtimeSeconds  network-wide play time as of this read, which the card's crest follows from
 * @param locale           the player's language, English when unknown
 * @param phase            the season phase when this was read, {@link SeasonPhase#MAINTENANCE} if unreadable
 * @param launch           when the network opens, {@code null} when no date has been announced
 */
public record AccessState(
        UUID minecraftAccount,
        @Nullable DiscordId discordId,
        @Nullable MemberState memberState,
        boolean accessActive,
        @Nullable Instant accessValidUntil,
        boolean donor,
        boolean admin,
        boolean packExempt,
        long playtimeSeconds,
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

    /** Returns the answer for a UUID nobody has linked, in a network whose phase is known. */
    public static AccessState unlinked(final UUID account, final SeasonPhase phase) {
        return new AccessState(account, null, null, false, null, false, false, false, 0L, Locale.ENGLISH, phase, null);
    }

    /** Returns whether a Discord account is linked to this UUID. */
    public boolean linked() {
        return discordId != null;
    }

    /** Returns the linked Discord account, if any. */
    public Optional<DiscordId> discordAccount() {
        return Optional.ofNullable(discordId);
    }

    /** Returns the end of the current run of access, if any. */
    public Optional<Instant> validUntil() {
        return Optional.ofNullable(accessValidUntil);
    }

    /** Returns when the network opens, if a date has been announced. */
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
     * Returns whether this account may join right now, in the phase this state was read in.
     * In {@code SMP} the admin flag stands in for access, so the admin who switches into {@code SMP} is not
     * disconnected.
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
