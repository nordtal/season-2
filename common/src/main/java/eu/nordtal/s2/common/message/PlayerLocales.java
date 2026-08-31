package eu.nordtal.s2.common.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * A player's language, read once at join and held for the session.
 * <p>
 * This is the shared component {@code docs/i18n.md} asks for: <b>one</b> place that does the
 * loading, the caching and the default, so that the proxy and all three Paper plugins behave
 * identically and nobody invents a second rule. Every user-visible string - a disconnect screen, a
 * pack prompt, a title, a boss bar, a board - is rendered against what this returns.
 * </p>
 *
 * <h2>The lookup order, and where it does not come from</h2>
 * The language is {@code discord_user.locale}, reached through {@code account_link}; a player with
 * no language role in Discord has {@code 'en'} there, which is the default and the fallback
 * everywhere. The Minecraft client's own language setting is <b>not</b> consulted - Discord and the
 * game showing different languages is exactly what having one source avoids. (The resource pack's
 * {@code lang/*.json} files are the one accepted exception, and nothing in this class can reach
 * them.)
 *
 * <h2>The caching trade-off, stated so nobody "fixes" it</h2>
 * The value is loaded on {@link #join(UUID)} and then <b>never refreshed</b> until the next join.
 * A player who changes their Discord language role mid-session keeps seeing the old language until
 * they rejoin. That is the intended behaviour and not a bug: the alternative is a database query
 * per rendered message, on paths that render constantly. One query per join against an indexed
 * lookup is cheap; one query per boss-bar tick is not.
 * <p>
 * {@link #of(UUID)} therefore <b>never queries</b>. For a player nobody called {@link #join(UUID)}
 * for it answers English rather than reaching for the database, because it is called from render
 * paths where blocking is not an option and a missing translation must degrade rather than throw.
 * </p>
 *
 * <h2>Where the query runs, settled 2026-09-01</h2>
 * {@link #join(UUID)} <b>blocks</b>: it is one JDBC round trip, and calling it from a Paper
 * {@code PlayerJoinEvent} handler puts that round trip on the server's main thread. On a healthy
 * database that is a millisecond and nobody notices; on a database that has stopped answering it is
 * however long the pool's {@code connectionTimeout} is, per join, with the whole server stopped
 * behind it - and the server every login passes through is {@code limbo}, where that would be the
 * network freezing rather than one backend hesitating.
 * <p>
 * So the Paper modules call {@link #joinAsync(UUID, Executor)} instead, and render against
 * {@link #of(UUID)} in the meantime - which answers English, which is the fallback this whole
 * document is built on. The blocking form is kept because it is what {@link #joinAsync} runs, and
 * because a process with no scheduler (a test, a tool) has no reason to go the long way round.
 * </p>
 *
 * <h2>Lifetime</h2>
 * A plain heap map, one instance per process, entries dropped by {@link #quit(UUID)}. It holds one
 * {@link Locale} per online player and dies with the process.
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
     * <p>
     * Calling it again re-reads and replaces the held value, which is what makes a rejoin pick a
     * changed language up.
     * </p>
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
            // A language lookup must never be able to fail a join. English is always a correct
            // answer, and it is cached like any other so that a database blip costs one query and
            // not one per rendered message for the rest of the session.
            LOGGER.warn("Could not read the language of {} - falling back to {}", mcUuid, Locales.DEFAULT, exception);
            locale = Locales.DEFAULT;
        }

        final Locale held = locale == null ? Locales.DEFAULT : locale;
        byPlayer.put(mcUuid, held);
        return held;
    }

    /**
     * Loads a player's language off the calling thread and holds it for the session.
     * <p>
     * This is what a Paper plugin calls from {@code PlayerJoinEvent}: the query is one round trip
     * against an indexed lookup, and it is still one round trip too many to run on the main thread
     * of a server that is on the login path. Until it completes, {@link #of(UUID)} answers English
     * for this player - so a German player may see one English line before the correct one replaces
     * it, which is the same degradation a missing translation already has.
     * </p>
     * <p>
     * The returned future <b>never completes exceptionally</b>: {@link #join(UUID)} swallows its own
     * failures and answers English, and this adds nothing on top. A caller that resumes on the main
     * thread should still check the player is <em>still online</em> before acting on the result, and
     * call {@link #quit(UUID)} if they are not - otherwise a player who left while the query was in
     * flight leaves an entry behind that nothing ever removes.
     * </p>
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
     * The language a player is being rendered to right now. Never queries, never blocks, never
     * throws.
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

    /**
     * Drops a player's language. Call this when they disconnect, or the map grows for the lifetime
     * of the process.
     *
     * @param mcUuid the Minecraft account that just left
     */
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
