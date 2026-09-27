package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.common.access.AdminTree;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.Sessions;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.data.Data;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Discord OAuth round trip: sending a browser to Discord and signing it in when it comes back. */
final class AuthFlow {

    private static final Logger log = LoggerFactory.getLogger(AuthFlow.class);

    private final UiSpec config;
    private final DiscordAuth discord;
    private final @Nullable Data data;
    private final @Nullable Sessions sessions;
    private final @Nullable AdminTree admins;

    AuthFlow(
            final UiSpec config,
            final DiscordAuth discord,
            final @Nullable Data data,
            final @Nullable Sessions sessions,
            final @Nullable AdminTree admins) {
        this.config = config;
        this.discord = discord;
        this.data = data;
        this.sessions = sessions;
        this.admins = admins;
    }

    private Data data() {
        return Objects.requireNonNull(data, "this route needs the database, which this instance has none of");
    }

    private Sessions sessions() {
        return Objects.requireNonNull(sessions, "this route needs sessions, which this instance has none of");
    }

    private AdminTree admins() {
        return Objects.requireNonNull(admins, "this route needs admins, which this instance has none of");
    }

    void login(final Context ctx) {
        final Optional<String> missing = discord.whatIsMissing();
        if (missing.isPresent()) {
            ctx.status(503).json(Map.of("error", "sign-in is not configured: " + missing.get()));
            return;
        }
        // A one-time value tied to this browser's session; a callback carrying anything else is not it.
        final String state = random();
        setSessionCookie(ctx, sessions().begin(state));
        ctx.redirect(discord.authorizeUrl(state).toString());
    }

    void callback(final Context ctx) {
        final String started = ctx.cookie(Sessions.COOKIE);
        // Read once and cleared in the same statement, so a replayed callback matches nothing.
        final Optional<String> expected = sessions().consumeState(started);
        final String state = ctx.queryParam("state");
        if (expected.isEmpty() || !expected.get().equals(state)) {
            ctx.status(400)
                    .json(Map.of("error", "this sign-in did not start in this browser - try again from the start"));
            return;
        }
        final String code = ctx.queryParam("code");
        if (code == null || code.isBlank()) {
            ctx.status(400).json(Map.of("error", "Discord sent no code"));
            return;
        }
        final DiscordAuth.Outcome outcome = discord.signIn(code);
        if (!outcome.ok()) {
            ctx.status(403).json(Map.of("error", outcome.refusal()));
            return;
        }
        final DiscordAuth.Account who = Objects.requireNonNull(outcome.account());
        // Whether they may in is the admin tree's answer; a tree with nobody in it lets the first sign-in claim root.
        final String signingIn = who.id();
        if (admins == null || !(admins().isAdmin(signingIn) || claimRoot(who))) {
            log.info("refused {} ({}): not an admin", who.name(), signingIn);
            ctx.status(403).json(Map.of("error", who.name() + " is in the guild but is not an admin"));
            return;
        }
        // A new row with a new id; the one the sign-in started in is dropped to avoid session fixation.
        final String id = sessions().signIn(who.id(), who.name(), who.roles());
        sessions().end(started);
        setSessionCookie(ctx, id);
        ctx.redirect("/");
    }

    /** The bootstrap: true when this sign-in just became the root of an empty admin tree. */
    private boolean claimRoot(final DiscordAuth.Account who) {
        if (!admins().claimRootIfNobody(who.id())) {
            return false;
        }
        log.warn(
                "{} ({}) signed in while nobody was an admin and is now the root of the admin tree",
                who.name(),
                who.id());
        data().audit().record("ADMIN_ROOT", who.id(), who.id(), null, "first sign-in while nobody was an admin");
        return true;
    }

    /**
     * The hourly sweep, with its failure swallowed on purpose.
     *
     * {@code scheduleWithFixedDelay} cancels the schedule for good the first time the task throws,
     * silently; catching here keeps it alive across a briefly unreachable database.
     */
    void sweepSessions() {
        try {
            sessions().sweep();
        } catch (RuntimeException e) {
            log.warn("could not sweep expired sessions - trying again in {}: {}", StewardUi.SWEEP, e.toString());
        }
    }

    /**
     * Sets the session cookie, marking it {@code Secure} whenever the request arrived over TLS.
     *
     * {@code X-Forwarded-Proto} can only ever turn the flag on, and a forged one only costs its
     * forger their own session.
     */
    private void setSessionCookie(final Context ctx, final String id) {
        final Cookie cookie = new Cookie(
                Sessions.COOKIE, id, "/", (int) lifetime().toSeconds(), overTls(ctx), true, null, SameSite.LAX);
        ctx.cookie(cookie);
    }

    private static boolean overTls(final Context ctx) {
        // The header may carry a list of hops; the first entry is the browser's own.
        final String forwarded = ctx.header("X-Forwarded-Proto");
        if (forwarded != null && !forwarded.isBlank()) {
            final int comma = forwarded.indexOf(',');
            final String first = comma < 0 ? forwarded : forwarded.substring(0, comma);
            return first.trim().equalsIgnoreCase("https");
        }
        return "https".equalsIgnoreCase(ctx.scheme());
    }

    private Duration lifetime() {
        return Duration.ofDays(config.sessionDays());
    }

    private static String random() {
        final byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
