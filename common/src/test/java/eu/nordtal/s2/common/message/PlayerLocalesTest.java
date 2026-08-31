package eu.nordtal.s2.common.message;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The caching contract of {@link PlayerLocales}, which is the part of it that is a decision rather
 * than a query: <em>when</em> a language change takes effect, and what a render path gets for a
 * player nobody loaded.
 * <p>
 * In memory, with a lambda for the {@link LocaleSource} - not a mock framework, and not a database:
 * the database side of the same component is exercised against a real container in
 * {@code AccessDirectoryIntegrationTest}. What is proved here is that the value is read exactly
 * once per join and never again, which no amount of container time would show more clearly.
 * </p>
 */
class PlayerLocalesTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final Map<UUID, Locale> stored = new ConcurrentHashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();
    private final PlayerLocales locales = new PlayerLocales(uuid -> {
        lookups.incrementAndGet();
        return stored.getOrDefault(uuid, Locales.DEFAULT);
    });

    @Test
    void joiningLoadsTheLanguageAndHoldsIt() {
        stored.put(PLAYER, Locale.GERMAN);

        assertEquals(Locale.GERMAN, locales.join(PLAYER));
        assertEquals(Locale.GERMAN, locales.of(PLAYER));
        assertEquals(1, locales.size());
    }

    @Test
    void renderingNeverQueriesAgainForTheRestOfTheSession() {
        stored.put(PLAYER, Locale.GERMAN);
        locales.join(PLAYER);

        for (int render = 0; render < 100; render++) {
            assertEquals(Locale.GERMAN, locales.of(PLAYER));
        }

        assertEquals(1, lookups.get(),
                "of() is called from boss bars and boards; one query per rendered message is exactly "
                        + "what holding the value for the session buys");
    }

    @Test
    void aLanguageChangedMidSessionTakesEffectOnTheNextJoin() {
        stored.put(PLAYER, Locale.GERMAN);
        locales.join(PLAYER);

        // The player picks the English role in Discord and the bot mirrors it into the database.
        stored.put(PLAYER, Locale.ENGLISH);
        assertEquals(Locale.GERMAN, locales.of(PLAYER), "still German for the rest of this session - by design");

        locales.quit(PLAYER);
        assertEquals(Locale.ENGLISH, locales.join(PLAYER));
    }

    @Test
    void aPlayerNobodyLoadedRendersInEnglishRatherThanQuerying() {
        assertEquals(Locale.ENGLISH, locales.of(PLAYER));
        assertEquals(Locale.ENGLISH, locales.of(null));
        assertEquals(0, lookups.get(), "of() must not reach a database from a render path");
    }

    @Test
    void quittingDropsTheEntrySoTheMapDoesNotGrowForever() {
        locales.join(PLAYER);
        assertEquals(1, locales.size());

        locales.quit(PLAYER);
        assertEquals(0, locales.size());
        locales.quit(PLAYER);
        assertEquals(0, locales.size(), "quitting twice is not an error");
        locales.quit(null);
    }

    @Test
    void aFailedLookupIsEnglishAndIsStillHeld() {
        final AtomicInteger attempts = new AtomicInteger();
        final PlayerLocales failing = new PlayerLocales(uuid -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("database is down");
        });

        assertEquals(Locale.ENGLISH, failing.join(PLAYER), "a language lookup must never fail a join");
        assertEquals(Locale.ENGLISH, failing.of(PLAYER));
        assertEquals(1, attempts.get(), "the fallback is cached too - a blip costs one query, not one per message");
    }

    @Test
    void aSourceThatAnswersNullStillYieldsALocale() {
        final PlayerLocales sloppy = new PlayerLocales(uuid -> null);

        assertNotNull(sloppy.join(PLAYER));
        assertEquals(Locale.ENGLISH, sloppy.of(PLAYER));
        assertEquals(Locale.ENGLISH, sloppy.join(null));
    }

    // ---------------------------------------------------------------- off the main thread

    @Test
    void joinAsyncLoadsOnTheExecutorItIsGivenAndNotOnTheCaller() throws Exception {
        // The whole point of the method: the JDBC round trip must not happen on the thread that
        // called it, because on Paper that thread is the server. Asserted by capturing the thread
        // the source actually ran on rather than by timing anything.
        stored.put(PLAYER, Locale.GERMAN);
        final java.util.concurrent.atomic.AtomicReference<Thread> ranOn =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();

        final PlayerLocales locales = new PlayerLocales(uuid -> {
            ranOn.set(Thread.currentThread());
            return Locale.GERMAN;
        });

        try {
            assertEquals(Locale.GERMAN, locales.joinAsync(PLAYER, executor).get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertNotNull(ranOn.get());
        assertNotEquals(Thread.currentThread(), ranOn.get());
    }

    @Test
    void ofAnswersEnglishUntilTheAsyncLoadLands() throws Exception {
        // The visible consequence, and the reason this is safe to do at all: a render path that
        // fires before the query returns gets the fallback rather than blocking or throwing. A
        // German player may therefore see one English line at the start of a session.
        stored.put(PLAYER, Locale.GERMAN);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();

        final PlayerLocales locales = new PlayerLocales(uuid -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Locale.GERMAN;
        });

        try {
            final var pending = locales.joinAsync(PLAYER, executor);
            assertEquals(Locale.ENGLISH, locales.of(PLAYER));

            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertEquals(Locale.GERMAN, locales.of(PLAYER));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void joinAsyncNeverCompletesExceptionally() throws Exception {
        // join() swallows its own failures and answers English; joinAsync adds nothing on top. A
        // future that completed exceptionally would put an unhandled failure on a scheduler thread
        // on a login path, which is the one place it must not be.
        final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        final PlayerLocales locales = new PlayerLocales(uuid -> {
            throw new IllegalStateException("the database is gone");
        });

        try {
            assertEquals(Locales.DEFAULT, locales.joinAsync(PLAYER, executor).get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(Locales.DEFAULT, locales.of(PLAYER));
    }
}
