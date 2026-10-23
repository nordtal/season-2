package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One person as a list shows them. {@code accessUntil} counts revoked grants, {@code accessActive} does not.
 *
 * @param memberState {@code MEMBER}, {@code LEFT} or {@code BANNED}, as text so a hand-written value shows
 * @param updated when the row last changed, which {@link AccessReader#people(int)} orders by
 * @param accessUntil the latest {@code valid_until} of any grant, revoked ones included. Null if none
 * @param playtimeSeconds the total online time, null if the account was never online
 * @param adminGrantedBy the admin who granted this one, null for the root and for non-admins
 * @param packExemptBy the admin who let this account play without the resource pack, null unless one did
 * @param aura the aura on the SMP, zero before the first change
 * @param spins the earned wheel spins left
 * @param online whether the proxy lists the linked Minecraft account as connected
 * @param lastSeen when the linked Minecraft account last left the network, null if it never did
 */
public record Person(
        DiscordId discordId,
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
        @Nullable Instant adminGrantedAt,
        @Nullable String packExemptBy,
        @Nullable Instant packExemptAt,
        int aura,
        int spins,
        boolean online,
        @Nullable Instant lastSeen) {}
