package eu.nordtal.season.steward.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.common.http.Reply;
import eu.nordtal.season.steward.config.WebSpec;
import io.javalin.Javalin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Tests the messages for a deployment that nobody can sign in to, before any call to Discord. */
class DiscordAuthTest {

    @Test
    void theMissingValueIsNamedRatherThanGuessedAt() {
        assertEquals("discord.client-id", missingFrom(new Values()));
        assertTrue(missingFrom(new Values().withClientId("an-application")).startsWith("discord.client-secret"));
        assertEquals(
                "discord.guild-id",
                missingFrom(new Values().withClientId("an-application").withClientSecret("shh")));
        // The admin tree in the database decides who may sign in, not a Discord role.
        assertTrue(new DiscordAuth(
                        new Values()
                                .withClientId("an-application")
                                .withClientSecret("shh")
                                .withGuildId("1234"),
                        "https://steward.example")
                .whatIsMissing()
                .isEmpty());
    }

    @Test
    void theRedirectUriIsWrittenDownNotGuessed() {
        // The redirect URI must not follow the Host header.
        assertEquals(
                "https://steward.nordtal.eu/auth/callback",
                new DiscordAuth(new Values(), "https://steward.nordtal.eu").redirectUri());
    }

    private static final List<String> GUIDANCE =
            List.of("deploy/nordtal.sh", "deploy/dev.env.example", "deploy/README.md", "README.md");

    private static final Pattern MENTION = Pattern.compile("[^\\s\"'`]*auth/callback");

    @Test
    void theGuidanceNamesTheRealRedirectUri() {
        // The path derives from DiscordAuth, so the test fails when the route moves.
        final String built = new DiscordAuth(new Values(), "https://steward.example").redirectUri();
        final String path = built.substring("https://steward.example".length());

        final List<String> wrong = new ArrayList<>();
        for (final String file : GUIDANCE) {
            final Matcher mention = MENTION.matcher(read(RepositoryRoot.path().resolve(file)));
            while (mention.find()) {
                final String named = pathOf(mention.group());
                if (!named.equals(path)) {
                    wrong.add(file + " says " + named);
                }
            }
        }

        assertEquals(
                List.of(),
                wrong,
                "the sign-in redirects to " + path
                        + ", and an instruction that names anything else sends an operator to Discord with"
                        + " a URI the sign-in will never present");
    }

    /** Removes the scheme and host from the front of a mention. */
    private static String pathOf(final String mention) {
        final String withoutScheme = mention.replaceFirst("^https?://", "");
        final int host = withoutScheme.indexOf('/');
        return host < 0 ? "/" + withoutScheme : withoutScheme.substring(host);
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String missingFrom(final WebSpec.DiscordSpec values) {
        return new DiscordAuth(values, "https://steward.example")
                .whatIsMissing()
                .orElseThrow();
    }

    /** Holds the three values, each empty until a test sets it. */
    private static final class Values implements WebSpec.DiscordSpec {

        private String clientId = "";
        private String clientSecret = "";
        private String guildId = "";

        Values withClientId(final String value) {
            clientId = value;
            return this;
        }

        Values withClientSecret(final String value) {
            clientSecret = value;
            return this;
        }

        Values withGuildId(final String value) {
            guildId = value;
            return this;
        }

        @Override
        public String clientId() {
            return clientId;
        }

        @Override
        public String clientSecret() {
            return clientSecret;
        }

        @Override
        public String guildId() {
            return guildId;
        }
    }

    @Test
    void cleartextGoesNowhereButHere() {
        final Values config = new Values();

        assertThrows(
                IllegalArgumentException.class,
                () -> new DiscordAuth(config, "https://steward.example", "http://discord.example"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiscordAuth(config, "https://steward.example", "http://10.0.0.5:8080"));

        // Allowed are the real API and a stand-in on a loopback port.
        assertDoesNotThrow(() -> new DiscordAuth(config, "https://steward.example", DiscordAuth.DISCORD_API));
        assertDoesNotThrow(() -> new DiscordAuth(config, "https://steward.example", "http://127.0.0.1:18093"));
    }

    private static final Instant NOON = Instant.parse("2026-11-23T12:00:00Z");

    private static Reply tooMany(final String body, final Map<String, List<String>> headers) {
        return new Reply(
                URI.create("https://discord.com/api/v10/oauth2/token"),
                429,
                body,
                HttpHeaders.of(headers, (name, value) -> true));
    }

    @Test
    void retryAfterReadsSecondsFromTheHeader() {
        assertEquals(
                Duration.ofMillis(2500),
                DiscordAuth.retryAfter(tooMany("", Map.of("Retry-After", List.of("2.5"))), NOON));
    }

    @Test
    void retryAfterReadsAnHttpDateFromTheHeader() {
        assertEquals(
                Duration.ofSeconds(90),
                DiscordAuth.retryAfter(
                        tooMany("", Map.of("Retry-After", List.of("Mon, 23 Nov 2026 12:01:30 GMT"))), NOON));
    }

    @Test
    void retryAfterFallsBackToTheBody() {
        assertEquals(
                Duration.ofMillis(1200),
                DiscordAuth.retryAfter(tooMany("{\"retry_after\":1.2,\"global\":false}", Map.of()), NOON));
        assertEquals(
                Duration.ofSeconds(7),
                DiscordAuth.retryAfter(
                        tooMany("{\"retry_after\":7}", Map.of("Retry-After", List.of("Mon, 23 Nov 2026 11:00:00 GMT"))),
                        NOON),
                "a date in the past says nothing, so the body decides");
    }

    @Test
    void retryAfterDefaultsWhenDiscordNamesNoWait() {
        assertEquals(DiscordAuth.DEFAULT_BACKOFF, DiscordAuth.retryAfter(tooMany("", Map.of()), NOON));
        assertEquals(
                DiscordAuth.DEFAULT_BACKOFF,
                DiscordAuth.retryAfter(tooMany("<html>", Map.of("Retry-After", List.of("soon"))), NOON));
        assertEquals(
                DiscordAuth.DEFAULT_BACKOFF,
                DiscordAuth.retryAfter(tooMany("{\"retry_after\":-3}", Map.of("Retry-After", List.of("0"))), NOON));
    }

    @Test
    void retryAfterIsCapped() {
        assertEquals(
                DiscordAuth.MAX_BACKOFF,
                DiscordAuth.retryAfter(tooMany("", Map.of("Retry-After", List.of("86400"))), NOON));
    }

    /** A stand-in Discord whose token endpoint and member route each answer 429 while their flag is set. */
    private static final class LimitedDiscord implements AutoCloseable {

        final AtomicInteger tokenHits = new AtomicInteger();
        final AtomicBoolean tokenLimited = new AtomicBoolean();
        final AtomicBoolean memberLimited = new AtomicBoolean();
        final AtomicBoolean memberLimitGlobal = new AtomicBoolean();
        final AtomicReference<Instant> now = new AtomicReference<>(NOON);
        private final Javalin server;
        final DiscordAuth auth;

        LimitedDiscord() {
            server = Javalin.create(cfg -> {
                        cfg.startup.showJavalinBanner = false;
                        cfg.routes.post("/oauth2/token", ctx -> {
                            tokenHits.incrementAndGet();
                            if (tokenLimited.get()) {
                                ctx.header("Retry-After", "30").status(429).result("{\"retry_after\":30}");
                                return;
                            }
                            ctx.result("{\"access_token\":\"ok\"}");
                        });
                        cfg.routes.get("/users/@me", ctx -> ctx.result("{\"id\":\"1\",\"username\":\"ally\"}"));
                        cfg.routes.get("/users/@me/guilds/1234/member", ctx -> {
                            if (memberLimited.get()) {
                                if (memberLimitGlobal.get()) {
                                    ctx.header("X-RateLimit-Global", "true");
                                }
                                ctx.header("Retry-After", "20").status(429).result("{\"retry_after\":20}");
                                return;
                            }
                            ctx.result("{\"nick\":\"Ally\"}");
                        });
                    })
                    .start(0);
            auth = new DiscordAuth(
                    new Values().withClientId("app").withClientSecret("secret").withGuildId("1234"),
                    "https://steward.example",
                    "http://127.0.0.1:" + server.port(),
                    now::get);
        }

        void advance(final long seconds) {
            now.set(now.get().plusSeconds(seconds));
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    @Test
    void aLimitedTokenEndpointPausesEverySignInForItsRetryAfter() {
        try (LimitedDiscord discord = new LimitedDiscord()) {
            discord.tokenLimited.set(true);

            final DiscordAuth.Outcome first = discord.auth.signIn("code-1");
            discord.advance(10);
            final DiscordAuth.Outcome second = discord.auth.signIn("code-2");

            assertEquals(Duration.ofSeconds(30), first.retryAfter());
            assertEquals(Duration.ofSeconds(20), second.retryAfter(), "the pause names what is left of it");
            assertEquals(1, discord.tokenHits.get(), "Discord must not be called again during Retry-After");

            discord.tokenLimited.set(false);
            discord.advance(20);
            assertTrue(discord.auth.signIn("code-3").ok(), "sign-in must resume once Retry-After expires");
            assertEquals(2, discord.tokenHits.get());
        }
    }

    @Test
    void aLimitedPerUserRouteFailsOnlyThatSignIn() {
        try (LimitedDiscord discord = new LimitedDiscord()) {
            discord.memberLimited.set(true);
            final DiscordAuth.Outcome limited = discord.auth.signIn("code-1");
            assertEquals(429, limited.status());
            assertEquals(Duration.ofSeconds(20), limited.retryAfter());

            discord.memberLimited.set(false);
            assertTrue(discord.auth.signIn("code-2").ok(), "the next sign-in must not wait for another user's limit");
        }
    }

    @Test
    void aGlobalLimitOnAPerUserRoutePausesEverySignIn() {
        try (LimitedDiscord discord = new LimitedDiscord()) {
            discord.memberLimited.set(true);
            discord.memberLimitGlobal.set(true);
            assertEquals(429, discord.auth.signIn("code-1").status());

            discord.memberLimited.set(false);
            discord.advance(5);
            final DiscordAuth.Outcome paused = discord.auth.signIn("code-2");
            assertEquals(Duration.ofSeconds(15), paused.retryAfter());
            assertEquals(1, discord.tokenHits.get(), "a global limit must stop the next token exchange");
        }
    }

    @Test
    void aGlobalFlagInTheBodyCountsAsGlobal() {
        assertTrue(DiscordAuth.global(tooMany("{\"retry_after\":1,\"global\":true}", Map.of())));
        assertTrue(DiscordAuth.global(tooMany("", Map.of("X-RateLimit-Global", List.of("true")))));
        assertFalse(DiscordAuth.global(tooMany("{\"retry_after\":1,\"global\":false}", Map.of())));
    }
}
