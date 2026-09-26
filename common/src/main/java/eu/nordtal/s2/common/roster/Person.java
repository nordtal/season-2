package eu.nordtal.s2.common.roster;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One person as a list shows them, with access figures computed in the same statement.
 *
 * {@code accessUntil} counts revoked grants and {@code accessActive} does not, so a revoked person shows a
 * date with an inactive flag and someone who never bought shows nothing. Serialised straight to JSON.
 *
 * @param memberState     {@code MEMBER}, {@code LEFT} or {@code BANNED}; text, so a hand-written value shows
 * @param updated         when the row last changed, what {@link RosterDirectory#people(int)} orders by
 * @param minecraftUuid   the linked Minecraft account, {@code null} when there is no link
 * @param linked          when that link was written, {@code null} when there is no link
 * @param accessUntil     the latest {@code valid_until} of any grant, revoked included; {@code null} if none
 * @param accessActive    whether a non-revoked grant covers this instant
 * @param playtimeSeconds total online time, {@code null} when the account has never been online
 * @param adminGrantedBy  the admin who granted this one, {@code null} for the root and for non-admins
 * @param adminGrantedAt  when this account became an admin, {@code null} unless it is one
 */
public record Person(
        String discordId,
        String memberState,
        boolean donor,
        boolean admin,
        String locale,
        Instant updated,
        @Nullable UUID minecraftUuid,
        @Nullable Instant linked,
        @Nullable Instant accessUntil,
        boolean accessActive,
        @Nullable String discordUsername,
        @Nullable Instant discordUsernameUpdated,
        @Nullable String discordDisplayName,
        @Nullable Instant discordDisplayNameUpdated,
        @Nullable String discordAvatarUrl,
        @Nullable Instant discordAvatarUrlUpdated,
        @Nullable String mcName,
        @Nullable Instant mcNameUpdated,
        @Nullable Long playtimeSeconds,
        @Nullable String adminGrantedBy,
        @Nullable Instant adminGrantedAt) {}
