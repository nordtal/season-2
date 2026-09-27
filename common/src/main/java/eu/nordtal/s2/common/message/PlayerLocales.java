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
 * Paper modules call {@link #joinAsync(UUID, Executor)}, because {@link #join(UUID)} blocks on JDBC.
 */
public final class PlayerLocales {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerLocales.class);

    private final LocaleSource source;
    private final Map<UUID, Locale> byPlayer = new ConcurrentHashMap<>();

    public PlayerLocales(final LocaleSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Loads a player's language and holds it for the session, replacing any held value.
     *
     * @return the language, English when the account is unknown or the lookup failed
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
     * The future never completes exceptionally; a caller resuming on the main thread must call {@link #quit(UUID)} if
     * the player has left.
     */
    public CompletableFuture<Locale> joinAsync(final UUID mcUuid, final Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return CompletableFuture.supplyAsync(() -> join(mcUuid), executor);
    }

    /** Returns the language a player is rendered in, English if not held; never queries, blocks or throws. */
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

    /** Returns how many players are currently held. */
    public int size() {
        return byPlayer.size();
    }
}
