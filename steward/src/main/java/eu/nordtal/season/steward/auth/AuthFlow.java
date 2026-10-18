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
import java.security.SecureRandom;
import java.time.Duration;
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
    private final @Nullable Data data;
    private final @Nullable Sessions sessions;
    private final @Nullable AdminTree admins;

    public AuthFlow(
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

    /** An empty admin tree without {@code discord.root-id} counts as missing. */
    public Optional<String> whatIsMissing() {
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

    public void login(final Context ctx) {
        final Optional<String> missing = whatIsMissing();
        if (missing.isPresent()) {
            throw new RequestRefused(503, ANSWER.signInUnconfigured(missing.get()));
        }
        final String state = random();
        setSessionCookie(ctx, sessions().begin(state), Sessions.SIGN_IN_WINDOW);
        ctx.redirect(discord.authorizeUrl(state).toString());
    }

    public void callback(final Context ctx) {
        final String started = ctx.cookie(Sessions.COOKIE);
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
        // An empty admin tree lets only the {@code discord.root-id} account claim root.
        final String signingIn = who.id();
        if (admins == null || !(admins().isAdmin(DiscordId.of(signingIn)) || claimRoot(who))) {
            log.info("refused {} ({}): not an admin", who.name(), signingIn);
            throw new RequestRefused(403, ANSWER.notAnAdmin(who.name()));
        }
        // A new session id prevents session fixation.
        final String id = sessions().signIn(DiscordId.of(who.id()), who.name());
        sessions().end(started);
        setSessionCookie(ctx, id, lifetime());
        ctx.redirect("/");
    }

    private boolean claimRoot(final DiscordAuth.Account who) {
        if (!who.id().equals(config.discord().rootId().strip())
                || !admins().claimRootIfNobody(DiscordId.of(who.id()))) {
            return false;
        }
        log.warn(
                "{} ({}) signed in while nobody was an admin and is now the root of the admin tree",
                who.name(),
                who.id());
        data().audit().record(AuditLine.about(JournalAction.ADMIN_ROOT, who.actor(), DiscordId.of(who.id()), null));
        return true;
    }

    /** A failure is logged and not thrown. */
    public void sweepSessions() {
        try {
            sessions().sweep();
        } catch (RuntimeException e) {
            log.warn("could not sweep expired sessions - trying again in {}: {}", SWEEP, e.toString());
        }
    }

    /** The cookie is {@code Secure} when the request arrived over TLS. */
    private static void setSessionCookie(final Context ctx, final String id, final Duration maxAge) {
        final Cookie cookie =
                new Cookie(Sessions.COOKIE, id, "/", (int) maxAge.toSeconds(), overTls(ctx), true, null, SameSite.LAX);
        ctx.cookie(cookie);
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
}
