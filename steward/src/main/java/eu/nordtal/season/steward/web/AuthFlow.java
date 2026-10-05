package eu.nordtal.season.steward.web;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.access.AdminTree;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.Sessions;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Discord OAuth round trip: sending a browser to Discord and signing it in when it comes back. */
final class AuthFlow {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final Logger log = LoggerFactory.getLogger(AuthFlow.class);

    private final WebSpec config;
    private final DiscordAuth discord;
    private final @Nullable Data data;
    private final @Nullable Sessions sessions;
    private final @Nullable AdminTree admins;

    AuthFlow(
            final WebSpec config,
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

    /**
     * What is missing before anybody can sign in, or empty when somebody can.
     *
     * An empty admin tree without a root id is such a gap, since nobody may claim it.
     */
    Optional<String> whatIsMissing() {
        return discord.whatIsMissing().or(this::rootUnclaimable);
    }

    private Optional<String> rootUnclaimable() {
        if (admins == null
                || !config.discord().rootId().isBlank()
                || !admins().admins().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("discord.root-id (nobody is an admin yet)");
    }

    void login(final Context ctx) {
        final Optional<String> missing = whatIsMissing();
        if (missing.isPresent()) {
            throw new RequestRefused(503, ANSWER.signInUnconfigured(missing.get()));
        }
        // A one-time value tied to this browser's session; a callback carrying anything else is not it.
        final String state = random();
        setSessionCookie(ctx, sessions().begin(state), Sessions.SIGN_IN_WINDOW);
        ctx.redirect(discord.authorizeUrl(state).toString());
    }

    void callback(final Context ctx) {
        final String started = ctx.cookie(Sessions.COOKIE);
        // Read once and cleared in the same statement, so a replayed callback matches nothing.
        final Optional<String> expected = sessions().consumeState(started);
        final String state = ctx.queryParam("state");
        if (expected.isEmpty() || !expected.get().equals(state)) {
            throw new RequestRefused(400, ANSWER.signInElsewhere());
        }
        final String code = ctx.queryParam("code");
        if (code == null || code.isBlank()) {
            throw new RequestRefused(400, ANSWER.noCode());
        }
        final DiscordAuth.Outcome outcome = discord.signIn(code);
        if (!outcome.ok()) {
            throw new RequestRefused(403, Objects.requireNonNull(outcome.refusal(), "a refused sign-in says why"));
        }
        final DiscordAuth.Account who = Objects.requireNonNull(outcome.account());
        // A tree with nobody in it lets the account of discord.root-id claim root, and nobody else.
        final String signingIn = who.id();
        if (admins == null || !(admins().isAdmin(DiscordId.of(signingIn)) || claimRoot(who))) {
            log.info("refused {} ({}): not an admin", who.name(), signingIn);
            throw new RequestRefused(403, ANSWER.notAnAdmin(who.name()));
        }
        // A new session id, so the one the sign-in started in cannot be fixated.
        final String id = sessions().signIn(DiscordId.of(who.id()), who.name(), who.roles());
        sessions().end(started);
        setSessionCookie(ctx, id, lifetime());
        ctx.redirect("/");
    }

    /** True when this sign-in is the root id's and just became the root of an empty admin tree. */
    private boolean claimRoot(final DiscordAuth.Account who) {
        if (!who.id().equals(config.discord().rootId().strip())
                || !admins().claimRootIfNobody(DiscordId.of(who.id()))) {
            return false;
        }
        log.warn(
                "{} ({}) signed in while nobody was an admin and is now the root of the admin tree",
                who.name(),
                who.id());
        data().audit()
                .record(AuditLine.about(
                        JournalAction.ADMIN_ROOT,
                        who.actor(),
                        DiscordId.of(who.id()),
                        TEXTS.journal().adminRoot()));
        return true;
    }

    /** Sweeps expired sessions, swallowing a failure, which would otherwise cancel the schedule for good. */
    void sweepSessions() {
        try {
            sessions().sweep();
        } catch (RuntimeException e) {
            log.warn("could not sweep expired sessions - trying again in {}: {}", Web.SWEEP, e.toString());
        }
    }

    /**
     * Sets the session cookie for {@code maxAge}, {@code Secure} whenever the request arrived over TLS.
     *
     * {@code X-Forwarded-Proto} can only turn the flag on, so a forged one costs only its forger's session.
     */
    private static void setSessionCookie(final Context ctx, final String id, final Duration maxAge) {
        final Cookie cookie =
                new Cookie(Sessions.COOKIE, id, "/", (int) maxAge.toSeconds(), overTls(ctx), true, null, SameSite.LAX);
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
