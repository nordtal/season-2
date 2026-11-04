package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
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
 * @param season the launch date, maintenance and game mode at this read, {@link SeasonState#UNREAD} if unreadable
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
        SeasonState season) {

    /** Maps a null season to {@link SeasonState#UNREAD}. */
    public AccessState {
        if (season == null) {
            season = SeasonState.UNREAD;
        }
    }

    /** Returns an unlinked state in {@link SeasonState#UNREAD}, as a fallback and test fixture. */
    public static AccessState unlinked(final UUID minecraftAccount) {
        return unlinked(minecraftAccount, SeasonState.UNREAD);
    }

    public static AccessState unlinked(final UUID account, final SeasonState season) {
        return new AccessState(account, null, null, false, null, false, false, false, 0L, Locale.ENGLISH, season);
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
        return Optional.ofNullable(season.launch());
    }

    /** Returns whether a bought access period still has time, also one that has not started. */
    public boolean accessBought() {
        return accessValidUntil != null;
    }

    public boolean linkedMember() {
        return linked() && memberState == MemberState.MEMBER;
    }

    /**
     * Returns whether this account may be on the network at {@code now}, by the gate's order.
     * An admin always may; anyone else only once the season has launched, and on {@code smp} only with access.
     * Maintenance does not change the answer: who may join waits in the waiting room, see {@link #waits(Instant)}.
     */
    public boolean mayJoin(final Instant now) {
        if (!linkedMember()) {
            return false;
        }
        if (admin) {
            return true;
        }
        if (!season.launched(now)) {
            return false;
        }
        return season.mode() == SeasonMode.HUNGER_GAMES || accessActive;
    }

    /** Returns whether this account, if it may join, is held in the waiting room by maintenance. */
    public boolean waits(final Instant now) {
        return season.maintenance() && !admin && mayJoin(now);
    }
}
