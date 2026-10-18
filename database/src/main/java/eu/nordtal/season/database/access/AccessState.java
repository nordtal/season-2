package eu.nordtal.season.database.access;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the login path needs about one Minecraft account and the network, from a single query.
 *
 * @param minecraftAccount the UUID asked about, even if nothing is linked to it
 * @param memberState the guild membership of the Discord account, null if unlinked
 * @param accessActive whether a grant that is not revoked covers this instant
 * @param accessValidUntil the end of the whole current run of access, null if none
 * @param admin whether the linked account has the admin flag mirrored from Discord
 * @param packExempt whether an admin let this account join without the resource pack
 * @param playtimeSeconds the network-wide play time at this read, which the crest of the card follows from
 * @param phase the season phase at this read, {@link SeasonPhase#MAINTENANCE} if unreadable
 * @param launch when the network opens, null if no date is announced
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

    /** Maps a null phase to {@link SeasonPhase#MAINTENANCE}. */
    public AccessState {
        if (phase == null) {
            phase = SeasonPhase.MAINTENANCE;
        }
    }

    /** Returns an unlinked state in {@link SeasonPhase#MAINTENANCE}, as a fallback and test fixture. */
    public static AccessState unlinked(final UUID minecraftAccount) {
        return unlinked(minecraftAccount, SeasonPhase.MAINTENANCE);
    }

    public static AccessState unlinked(final UUID account, final SeasonPhase phase) {
        return new AccessState(account, null, null, false, null, false, false, false, 0L, Locale.ENGLISH, phase, null);
    }

    public boolean linked() {
        return discordId != null;
    }

    public Optional<DiscordId> discordAccount() {
        return Optional.ofNullable(discordId);
    }

    public Optional<Instant> validUntil() {
        return Optional.ofNullable(accessValidUntil);
    }

    public Optional<Instant> launchAt() {
        return Optional.ofNullable(launch);
    }

    /** Returns whether a bought access period still has time, also one that has not started. */
    public boolean accessBought() {
        return accessValidUntil != null;
    }

    public boolean linkedMember() {
        return linked() && memberState == MemberState.MEMBER;
    }

    /**
     * Returns whether this account may join now, in the phase of this read.
     * In {@code SMP} the admin flag stands in for access, so the admin who switches into {@code SMP} stays connected.
     */
    public boolean mayJoin() {
        if (!linkedMember()) {
            return false;
        }
        return switch (phase) {
            // Only SMP asks for more than a linked member.
            case PRE_EVENT, START_EVENT, MAINTENANCE -> true;
            case SMP -> accessActive || admin;
            // Before the network has opened, only an admin may be on it.
            case PRE_LAUNCH -> admin;
        };
    }
}
