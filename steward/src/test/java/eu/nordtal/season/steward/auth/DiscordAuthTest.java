package eu.nordtal.season.steward.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.steward.config.WebSpec;
import io.javalin.Javalin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void backsOffWhenDiscordAnswersTooManyRequests() {
        final AtomicInteger hits = new AtomicInteger();
        final AtomicBoolean rateLimited = new AtomicBoolean(true);
        final Javalin fakeDiscord = Javalin.create(cfg -> {
                    cfg.startup.showJavalinBanner = false;
                    cfg.routes.post("/oauth2/token", ctx -> {
                        hits.incrementAndGet();
                        if (rateLimited.get()) {
                            ctx.header("Retry-After", "30").status(429).result("{\"retry_after\":30}");
                            return;
                        }
                        ctx.result("{\"access_token\":\"ok\"}");
                    });
                    cfg.routes.get("/users/@me", ctx -> ctx.result("{\"id\":\"1\",\"username\":\"ally\"}"));
                    cfg.routes.get("/users/@me/guilds/1234/member", ctx -> ctx.result("{\"nick\":\"Ally\"}"));
                })
                .start(0);
        try {
            final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T12:00:00Z"));
            final DiscordAuth auth = new DiscordAuth(
                    new Values().withClientId("app").withClientSecret("secret").withGuildId("1234"),
                    "https://steward.example",
                    "http://127.0.0.1:" + fakeDiscord.port(),
                    now::get);

            final DiscordAuth.Outcome first = auth.signIn("code-1");
            now.set(now.get().plusSeconds(10));
            final DiscordAuth.Outcome second = auth.signIn("code-2");

            assertEquals(429, first.status(), "429 must propagate");
            assertEquals(429, second.status(), "a call during Retry-After must return 429");
            assertEquals(1, hits.get(), "Discord must not be called again during Retry-After backoff");

            rateLimited.set(false);
            now.set(now.get().plusSeconds(20));
            final DiscordAuth.Outcome resumed = auth.signIn("code-3");

            assertTrue(resumed.ok(), "sign-in must resume once Retry-After expires");
            assertEquals(2, hits.get(), "Discord should be called again once Retry-After expires");
        } finally {
            fakeDiscord.stop();
        }
    }
}
