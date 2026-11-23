package eu.nordtal.season.steward.auth;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.access.AdminTree;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs the Discord OAuth sign-in. {@link #login} redirects to Discord, and {@link #callback} signs the browser in. */
public final class AuthFlow {

    public static final Duration SWEEP = Duration.ofHours(1);

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final Logger log = LoggerFactory.getLogger(AuthFlow.class);

    private final WebSpec config;
    private final DiscordAuth discord;
    private final Data data;
    private final Sessions sessions;
    private final AdminTree admins;
    private final SignInAllowance allowance;

    public AuthFlow(
            final WebSpec config,
            final DiscordAuth discord,
            final Data data,
            final Sessions sessions,
            final AdminTree admins,
            final InstantSource clock) {
        this.config = config;
        this.discord = discord;
        this.data = data;
        this.sessions = sessions;
        this.admins = admins;
        this.allowance = new SignInAllowance(clock, config.discord().clientSecret());
    }

    /** An empty admin tree without {@code discord.root-id} counts as missing. */
    public Optional<String> whatIsMissing() {
        return discord.whatIsMissing().or(this::rootUnclaimable);
    }

    private Optional<String> rootUnclaimable() {
        if (!config.discord().rootId().isBlank() || !admins.admins().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("discord.root-id (nobody is an admin yet)");
    }

    /** Redirects to Discord with a new OAuth state, which only the cookie carries until the callback. */
    public void login(final Context ctx) {
        final Optional<String> missing = whatIsMissing();
        if (missing.isPresent()) {
            throw new RequestRefused(503, ANSWER.signInUnconfigured(missing.get()));
        }
        final String state = random();
        setCookie(ctx, Sessions.COOKIE, state, Sessions.SIGN_IN_WINDOW);
        ctx.redirect(discord.authorizeUrl(state).toString());
    }

    public void callback(final Context ctx) {
        final String expected = ctx.cookie(Sessions.COOKIE);
        final String state = ctx.queryParam("state");
        if (expected == null
                || state == null
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
            throw new RequestRefused(400, ANSWER.signInElsewhere());
        }
        final String id;
        try {
            id = signIn(ctx, ctx.queryParam("code"));
        } catch (final RuntimeException failed) {
            // The state is used once, so a failed callback cannot be answered again with another code.
            ctx.removeCookie(Sessions.COOKIE, "/");
            throw failed;
        }
        setCookie(ctx, Sessions.COOKIE, id, lifetime());
        ctx.redirect("/");
    }

    /** Returns the new session's id. A new id prevents session fixation. */
    private String signIn(final Context ctx, final @Nullable String code) {
        if (code == null || code.isBlank()) {
            throw new RequestRefused(400, ANSWER.noCode());
        }
        final String client = clientAddress(ctx);
        final Optional<Duration> wait = allowance.admit(ctx.cookie(SignInAllowance.COOKIE), client);
        if (wait.isPresent()) {
            log.warn("sign-in exchange rate-limited: client={} wait={}", client, wait.get());
            throw RequestRefused.retryAfter(429, wait.get(), ANSWER::tooManySignIns);
        }
        final DiscordAuth.Outcome outcome = discord.signIn(code);
        final Duration discordWait = outcome.retryAfter();
        if (discordWait != null) {
            throw RequestRefused.retryAfter(429, discordWait, ANSWER::tooManySignIns);
        }
        if (!outcome.ok()) {
            throw new RequestRefused(
                    outcome.status(), Objects.requireNonNull(outcome.refusal(), "a refused sign-in says why"));
        }
        final DiscordAuth.Account who = Objects.requireNonNull(outcome.account());
        // An empty admin tree lets only the {@code discord.root-id} account claim root.
        final String signingIn = who.id();
        if (!(admins.isAdmin(DiscordId.of(signingIn)) || claimRoot(who))) {
            log.info("sign-in refused, not an admin: actor=\"{}\" member={}", who.name(), signingIn);
            throw new RequestRefused(403, ANSWER.notAnAdmin(who.name()));
        }
        final String session = sessions.signIn(DiscordId.of(who.id()), who.name());
        setCookie(ctx, SignInAllowance.COOKIE, allowance.knownCookie(who.id()), SignInAllowance.KNOWN_FOR);
        return session;
    }

    /** Returns the client address as Caddy forwards it in {@code Cf-Connecting-IP}, or the peer address. */
    private static String clientAddress(final Context ctx) {
        // Caddy overwrites the header with the address it resolved, and Steward publishes no port of its own.
        final String forwarded = ctx.header("Cf-Connecting-IP");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.strip();
        }
        return ctx.ip();
    }

    private boolean claimRoot(final DiscordAuth.Account who) {
        if (!who.id().equals(config.discord().rootId().strip()) || !admins.claimRootIfNobody(DiscordId.of(who.id()))) {
            return false;
        }
        log.warn("root admin access claimed: actor=\"{}\" member={}", who.name(), who.id());
        data.audit().record(AuditLine.about(JournalAction.ADMIN_ROOT, who.actor(), DiscordId.of(who.id()), null));
        return true;
    }

    /** A failure is logged and not thrown. */
    public void sweepSessions() {
        try {
            sessions.sweep();
        } catch (RuntimeException e) {
            log.warn("expired sessions not swept: retry={} cause={}", SWEEP, e.toString());
        }
    }

    /** The cookie is {@code Secure} when the request arrived over TLS. */
    private static void setCookie(final Context ctx, final String name, final String value, final Duration maxAge) {
        ctx.cookie(new Cookie(name, value, "/", (int) maxAge.toSeconds(), overTls(ctx), true, null, SameSite.LAX));
    }

    private static boolean overTls(final Context ctx) {
        // The header may list several hops. The first entry is the browser's.
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

    /** Ends the session of the caller and removes its cookie. */
    public void logout(final Context ctx) {
        sessions.end(ctx.cookie(Sessions.COOKIE));
        ctx.removeCookie(Sessions.COOKIE, "/");
        ctx.status(204);
    }
}
