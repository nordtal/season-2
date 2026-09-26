package eu.nordtal.s2.common.message;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds each player's language, read once at join from {@code discord_user.locale}.
 *
 * The client's own language setting is not consulted. The value is not refreshed until the next join,
 * so rendering never queries. {@link #of(UUID)} never blocks and answers English for a player not held.
 * Paper modules call {@link #joinAsync(UUID, Executor)}, because {@link #join(UUID)} blocks on JDBC.
 * Entries are dropped by {@link #quit(UUID)}.
 */
public final class PlayerLocales {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerLocales.class);

    private final LocaleSource source;
    private final Map<UUID, Locale> byPlayer = new ConcurrentHashMap<>();

    /**
     * @param source where a language is read from at join; in every module this is
     *               {@code accessDirectory::locale}
     */
    public PlayerLocales(final LocaleSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Loads a player's language and holds it for the session. Call this once, when they join.
     *
     * Calling it again re-reads and replaces the held value, which is what makes a rejoin pick a
     * changed language up.
     *
     * @param mcUuid the Minecraft account that just joined
     * @return the language to render everything for this session in; English when the account is
     *         unknown or the lookup failed
     */
    public Locale join(final UUID mcUuid) {
        if (mcUuid == null) {
            return Locales.DEFAULT;
        }

        Locale locale;
        try {
            locale = source.localeOf(mcUuid);
        } catch (final RuntimeException exception) {
            // Never fail a join; English is cached so a database blip costs one query, not one per message.
            LOGGER.warn("Could not read the language of {} - falling back to {}", mcUuid, Locales.DEFAULT, exception);
            locale = Locales.DEFAULT;
        }

        final Locale held = locale == null ? Locales.DEFAULT : locale;
        byPlayer.put(mcUuid, held);
        return held;
    }

    /**
     * Loads a player's language off the calling thread and holds it for the session.
     *
     * This is what a Paper plugin calls from {@code PlayerJoinEvent}: the query is one round trip
     * against an indexed lookup, and it is still one round trip too many to run on the main thread
     * of a server that is on the login path. Until it completes, {@link #of(UUID)} answers English
     * for this player - so a German player may see one English line before the correct one replaces
     * it, which is the same degradation a missing translation already has.
     *
     * The returned future <b>never completes exceptionally</b>: {@link #join(UUID)} swallows its own
     * failures and answers English, and this adds nothing on top. A caller that resumes on the main
     * thread should still check the player is <em>still online</em> before acting on the result, and
     * call {@link #quit(UUID)} if they are not - otherwise a player who left while the query was in
     * flight leaves an entry behind that nothing ever removes.
     *
     * @param mcUuid   the Minecraft account that just joined
     * @param executor where the query runs; on Paper this is
     *                 {@code task -> server.getScheduler().runTaskAsynchronously(plugin, task)},
     *                 which is the pool that exists for exactly this
     * @return the language, once it is known
     */
    public CompletableFuture<Locale> joinAsync(final UUID mcUuid, final Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return CompletableFuture.supplyAsync(() -> join(mcUuid), executor);
    }

    /**
     * Returns the language a player is rendered in; never queries, blocks or throws.
     *
     * @param mcUuid the Minecraft account, may be {@code null}
     * @return the language loaded at join, or English if this player is not held
     */
    public Locale of(final UUID mcUuid) {
        if (mcUuid == null) {
            return Locales.DEFAULT;
        }
        return byPlayer.getOrDefault(mcUuid, Locales.DEFAULT);
    }

    /** Drops a player's language; call it on disconnect, or the map grows for the process's lifetime. */
    public void quit(final UUID mcUuid) {
        if (mcUuid != null) {
            byPlayer.remove(mcUuid);
        }
    }

    /** @return how many players are currently held, mostly for tests and logging */
    public int size() {
        return byPlayer.size();
    }
}
