package eu.nordtal.s2.common.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Checks the caching contract of {@link PlayerLocales}: when a change takes effect, and the fallback.
 *
 * In memory with a lambda source; the database side runs in {@code AccessDirectoryIntegrationTest}.
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

        assertEquals(
                1,
                lookups.get(),
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

    @Test
    void joinAsyncLoadsOnTheExecutorItIsGivenAndNotOnTheCaller() throws Exception {
        // The JDBC round trip must not run on the calling thread, which on Paper is the server's.
        stored.put(PLAYER, Locale.GERMAN);
        final java.util.concurrent.atomic.AtomicReference<Thread> ranOn =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();

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
        // A render before the query returns gets English rather than blocking or throwing.
        stored.put(PLAYER, Locale.GERMAN);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();

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
        // joinAsync must not complete exceptionally on a login path; join() already answers English.
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
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
