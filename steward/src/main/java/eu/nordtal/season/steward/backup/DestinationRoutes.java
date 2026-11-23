package eu.nordtal.season.steward.backup;

import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.internalapi.backup.BackupDestinationWire;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.journal.Journaled;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.journal.Unjournaled;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The routes that connect Google accounts as backup destinations, and the internal routes the series call.
 * The internal routes take a series' Bearer token and no session. The Caddyfile keeps them off the internet.
 */
public final class DestinationRoutes {

    private static final Logger log = LoggerFactory.getLogger(DestinationRoutes.class);

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    static final String CALLBACK = "/api/backup-destinations/google/callback";

    /** Where the callback sends the browser back to. */
    static final String PAGE = "/operations/backups";

    /** How long a consent may take before its state expires. */
    static final Duration CONSENT = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Everything the backups page shows about the remote copies. */
    public record BackupDestinations(
            boolean offered,
            List<Destinations.BackupAccount> accounts,
            List<Destinations.BackupSeries> series,
            List<Destinations.BackupRepository> repositories) {}

    public record GoogleStart(String authorizeUrl) {}

    /** The account a series copies to, or {@code null} for none. */
    public record SeriesChoice(@Nullable UUID accountId) {}

    /** A recovery file, which the page saves as a download. */
    public record RecoveryDownload(String name, String text) {}

    /** A consent that has started and not come back. */
    private record Pending(String discordId, String verifier, Instant expires) {}

    private final @Nullable Destinations destinations;
    private final GoogleDrive drive;
    private final String redirectUri;
    private final Map<String, String> tokens;
    private final AuditDirectory audit;
    private final Function<Context, DiscordAuth.Account> accounts;
    private final Clock clock;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * @param destinations the store, or {@code null} without a backup key
     * @param tokens the Bearer token of each series, a blank one refusing every call of that series
     */
    public DestinationRoutes(
            final @Nullable Destinations destinations,
            final GoogleDrive drive,
            final String publicUrl,
            final Map<String, String> tokens,
            final AuditDirectory audit,
            final Function<Context, DiscordAuth.Account> accounts,
            final Clock clock) {
        this.destinations = destinations;
        this.drive = drive;
        this.redirectUri = publicUrl + CALLBACK;
        this.tokens = Map.copyOf(tokens);
        this.audit = audit;
        this.accounts = accounts;
        this.clock = clock;
    }

    public void register(final JavalinConfig cfg) {
        cfg.routes.get("/api/backup-destinations", this::overview, Gate.KEY_HELD);
        cfg.routes.post(
                "/api/backup-destinations/google/start", this::start, Gate.KEY_FRESH, Unjournaled.CEREMONY_START);
        cfg.routes.get(CALLBACK, this::callback, Gate.KEY_HELD);
        cfg.routes.post(
                "/api/backup-destinations/accounts/{id}/disconnect",
                this::disconnect,
                Gate.KEY_FRESH,
                Journaled.as(JournalAction.DISCONNECT_BACKUP_ACCOUNT));
        cfg.routes.put(
                "/api/backup-destinations/series/{series}",
                this::choose,
                Gate.KEY_FRESH,
                Journaled.as(JournalAction.CHOOSE_BACKUP_ACCOUNT));
        cfg.routes.post(
                "/api/backup-destinations/accounts/{id}/repositories/{series}/recovery",
                this::recovery,
                Gate.KEY_FRESH,
                Journaled.as(JournalAction.DOWNLOAD_RECOVERY_FILE));
        cfg.routes.get(BackupDestinationWire.CREDENTIALS, this::credentials, Gate.ANYONE);
        cfg.routes.post(BackupDestinationWire.REPORT, this::report, Gate.ANYONE, Unjournaled.SERVICE_REPORT);
    }

    private boolean offered() {
        return destinations != null && drive.offered();
    }

    private Destinations store() {
        if (destinations == null || !drive.offered()) {
            throw new RequestRefused(503, ANSWER.driveUnconfigured());
        }
        return destinations;
    }

    void overview(final Context ctx) {
        if (destinations == null) {
            ctx.json(new BackupDestinations(false, List.of(), List.of(), List.of()));
            return;
        }
        ctx.json(new BackupDestinations(
                offered(), destinations.accounts(), destinations.series(), destinations.repositories()));
    }

    void start(final Context ctx) {
        store();
        final Instant now = clock.instant();
        pending.values().removeIf(consent -> consent.expires().isBefore(now));
        final String state = random(32);
        final String verifier = random(48);
        pending.put(state, new Pending(accounts.apply(ctx).id(), verifier, now.plus(CONSENT)));
        ctx.json(
                new GoogleStart(drive.authorizeUrl(redirectUri, state, verifier).toString()));
    }

    /** Finishes a consent. Every outcome redirects to the backups page, which names a failure by its code. */
    void callback(final Context ctx) {
        final @Nullable String state = ctx.queryParam("state");
        final @Nullable Pending consent = state == null ? null : pending.remove(state);
        final DiscordAuth.Account admin = accounts.apply(ctx);
        if (consent == null
                || consent.expires().isBefore(clock.instant())
                || !consent.discordId().equals(admin.id())) {
            ctx.redirect(PAGE + "?google=expired");
            return;
        }
        final @Nullable String code = ctx.queryParam("code");
        if (code == null || code.isBlank()) {
            ctx.redirect(PAGE + "?google=declined");
            return;
        }
        if (!offered()) {
            ctx.redirect(PAGE + "?google=unconfigured");
            return;
        }
        try {
            final GoogleDrive.Tokens tokens = drive.exchange(code, consent.verifier(), redirectUri);
            final GoogleDrive.Identity identity = drive.identity(tokens.accessToken());
            final GoogleDrive.Folder folder = drive.folder(tokens.accessToken());
            store().connect(identity, folder, tokens.refreshToken());
            log.info("backup account connected: actor={} email=\"{}\"", admin.id(), identity.email());
        } catch (final IOException failed) {
            log.warn("backup account not connected: actor={} reason=\"{}\"", admin.id(), failed.getMessage());
            ctx.redirect(PAGE + "?google=failed");
            return;
        }
        journal(JournalAction.CONNECT_BACKUP_ACCOUNT, admin);
        ctx.redirect(PAGE + "?google=connected");
    }

    /** A GET answers 302 on success and failure alike, so the callback writes its line itself. */
    private void journal(final JournalAction action, final DiscordAuth.Account admin) {
        try {
            audit.record(new AuditLine(action, admin.actor(), null, null, null));
        } catch (final RuntimeException failed) {
            log.error("journal entry not written: action={}", action, failed);
        }
    }

    void disconnect(final Context ctx) {
        final Destinations store = store();
        final UUID id = accountId(ctx.pathParam("id"));
        final Optional<String> token = store.refreshToken(id);
        if (token.isEmpty()) {
            throw new RequestRefused(404, ANSWER.noBackupAccount());
        }
        try {
            drive.revoke(token.get());
        } catch (final IOException failed) {
            // The token goes either way, so a disconnect never waits on Google.
            log.warn("backup account token not revoked: account={} reason=\"{}\"", id, failed.getMessage());
        }
        store.disconnect(id);
        Journaling.line(ctx, null, null);
        ctx.status(204);
    }

    void choose(final Context ctx) {
        final Destinations store = store();
        final String series = series(ctx.pathParam("series"));
        final SeriesChoice choice = ctx.bodyAsClass(SeriesChoice.class);
        if (!store.choose(series, choice.accountId())) {
            throw new RequestRefused(404, ANSWER.noBackupAccount());
        }
        Journaling.line(ctx, null, null);
        ctx.status(204);
    }

    void recovery(final Context ctx) {
        final Destinations store = store();
        final UUID id = accountId(ctx.pathParam("id"));
        final String series = series(ctx.pathParam("series"));
        final Destinations.Recovery recovery =
                store.takeRecovery(id, series).orElseThrow(() -> new RequestRefused(410, ANSWER.recoveryTaken()));
        Journaling.line(ctx, null, null);
        ctx.header("Cache-Control", "no-store");
        ctx.json(new RecoveryDownload(RecoveryFile.name(series), RecoveryFile.text(recovery)));
    }

    void credentials(final Context ctx) {
        final String series = authorized(ctx);
        ctx.header("Cache-Control", "no-store");
        if (destinations == null) {
            ctx.status(204);
            return;
        }
        final Optional<BackupDestinationWire.Credentials> found =
                destinations.credentials(series, drive.clientId(), drive.clientSecret());
        if (found.isEmpty()) {
            ctx.status(204);
            return;
        }
        if (!drive.offered()) {
            throw new RequestRefused(503, ANSWER.driveUnconfigured());
        }
        ctx.json(found.get());
    }

    void report(final Context ctx) {
        final String series = authorized(ctx);
        final BackupDestinationWire.Report report = ctx.bodyAsClass(BackupDestinationWire.Report.class);
        if (destinations != null) {
            final boolean kept = destinations.report(series, accountId(report.accountId()), report, clock.instant());
            if (!kept) {
                log.info("backup copy report ignored, account no longer active: series={}", series);
            }
        }
        ctx.status(204);
    }

    /** Returns the series of an internal call whose Bearer token matches that series' token. */
    private String authorized(final Context ctx) {
        final String series = series(ctx.pathParam("series"));
        final String expected = Objects.requireNonNullElse(tokens.get(series), "");
        final @Nullable String header = ctx.header("Authorization");
        if (expected.isBlank() || header == null || !matches(header, "Bearer " + expected)) {
            throw new RequestRefused(401, ANSWER.keyNotHeld());
        }
        return series;
    }

    static boolean matches(final String sent, final String expected) {
        return MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String series(final String series) {
        if (!Destinations.SERIES.contains(series)) {
            throw new RequestRefused(404, ANSWER.noBackupSeries(series));
        }
        return series;
    }

    private static UUID accountId(final String id) {
        try {
            return UUID.fromString(id);
        } catch (final IllegalArgumentException notAnId) {
            throw new RequestRefused(404, ANSWER.noBackupAccount());
        }
    }

    private static String random(final int bytes) {
        final byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
