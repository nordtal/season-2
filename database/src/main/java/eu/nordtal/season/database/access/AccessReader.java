package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import java.time.InstantSource;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * What every process reads about a member: link, access, language, profiles, admins and the roster.
 * One instance per process, over a connection pool that the caller owns and closes.
 */
public interface AccessReader {

    /**
     * Uses a connection pool that the caller owns, such as the HikariCP pool of a plugin.
     *
     * @param clock when link codes expire
     */
    static AccessReader using(final DataSource dataSource, final InstantSource clock) {
        return JdbiAccessDirectory.borrowing(dataSource, clock);
    }

    Optional<UUID> linkedMinecraftAccount(DiscordId discordId);

    Optional<DiscordId> linkedDiscordAccount(UUID mcUuid);

    /**
     * Returns the access state and the season phase in one round trip.
     *
     * @return the state. {@link AccessState#linked()} is false for an unlinked UUID, and the phase is always set
     */
    AccessState accessState(UUID mcUuid);

    /** Returns the language a Discord account reads, the network's default if it chose none. */
    Locale language(DiscordId discordId);

    /**
     * Returns who each of these accounts is in one round trip, without unlinked accounts.
     *
     * Blocking, so never call it on a main thread of a server. It throws if the database cannot answer.
     */
    List<PlayerIdentity> identities(Collection<PlayerId> players);

    /** Returns whether the donor flag is set, false for an unknown user. */
    boolean isDonor(DiscordId discordId);

    /** Returns the Discord profile last observed for this account, or {@link DiscordProfile#EMPTY}. It never throws. */
    DiscordProfile discordProfile(DiscordId discordId);

    MinecraftProfile minecraftProfile(DiscordId discordId);

    /** Returns every grant of one user, newest window first, including expired and revoked ones. */
    List<AccessGrant> grantsOf(DiscordId discordId);

    Set<String> admins();

    /** Returns the Minecraft account of every admin with a link. */
    Set<UUID> adminMinecraftAccounts();

    /**
     * Returns the newest {@code OPEN} purchase request of this account, if any.
     * Blocking: never call it on a main thread or on the login path.
     */
    Optional<OpenPayment> openPayment(DiscordId discordId);

    /**
     * Returns everyone the bot knows with their link and access, one row per person, newest update first.
     * The Discord id breaks ties, so a page is stable across calls.
     */
    List<Person> people(int limit);

    Optional<Person> personOf(DiscordId discordId);
}
