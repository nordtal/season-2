package eu.nordtal.s2.common.access;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the login path needs to know about one Minecraft account, answered by a single
 * query.
 * <p>
 * The proxy asks three questions in order - linked? member and not banned? access active? - and
 * each one has its own disconnect screen. They are three fields of one record rather than three
 * calls because a login must not cost three round trips to PostgreSQL.
 * </p>
 *
 * @param minecraftAccount the UUID that was asked about
 * @param discordId        the linked Discord account, {@code null} when the UUID is not linked
 * @param memberState      guild membership of that Discord account, {@code null} when unlinked
 * @param accessActive     whether a non-revoked grant covers this instant
 * @param accessValidUntil the end of the current run of access, {@code null} when there is none;
 *                         this is the end of the whole appended chain, not of one grant
 * @param donor            whether the linked account has the permanent donor flag
 * @param locale           the player's language, English when unknown - never {@code null}
 */
public record AccessState(
        UUID minecraftAccount,
        String discordId,
        MemberState memberState,
        boolean accessActive,
        Instant accessValidUntil,
        boolean donor,
        Locale locale) {

    /**
     * The answer for a UUID that has never been linked: no Discord account, no membership, no
     * access, English.
     *
     * @param minecraftAccount the UUID that was asked about
     * @return an unlinked state
     */
    public static AccessState unlinked(final UUID minecraftAccount) {
        return new AccessState(minecraftAccount, null, null, false, null, false, Locale.ENGLISH);
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
     * The whole login decision in one place, so no caller re-derives it.
     *
     * @return whether this account may join right now
     */
    public boolean mayJoin() {
        return linked() && memberState == MemberState.MEMBER && accessActive;
    }
}
