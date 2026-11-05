package eu.nordtal.season.steward.season;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonOpening;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.online.OnlinePlayer;
import eu.nordtal.season.database.season.MaintenanceSpan;
import eu.nordtal.season.database.season.SeasonChange;
import eu.nordtal.season.database.update.RunRecord;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.journal.Journaled;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.journal.Unjournaled;
import eu.nordtal.season.steward.stack.ServicesApi;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import eu.nordtal.season.steward.update.Updates;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** {@code /api/season}: the launch date, maintenance and the game mode that the router follows. */
public final class SeasonRoutes {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    /** How many recent runs are searched for the start by hand of the resting server. */
    private static final int HAND_START_RUNS = 50;

    /** The subject of the whole player list. */
    private static final String NETWORK = "proxy";

    private final Data data;
    private final Function<Context, DiscordAuth.Account> accounts;
    private final Supplier<ServicesApi.Online> online;
    private final Clock clock;

    /** @param online the players online by service, read fresh */
    public SeasonRoutes(
            final Data data,
            final Function<Context, DiscordAuth.Account> accounts,
            final Supplier<ServicesApi.Online> online,
            final Clock clock) {
        this.data = data;
        this.accounts = accounts;
        this.online = online;
        this.clock = clock;
    }

    /**
     * The answer to {@code GET /api/season}.
     *
     * @param launch the launch date, {@code null} while the season is closed
     * @param anchoredGrants how many access periods move with the launch date
     * @param lastSwitch the newest mode switch in any status, {@code null} before the first
     * @param handStart the start by hand of the resting server since the last switch, {@code null} without one
     */
    public record Router(
            @Nullable Instant launch,
            SeasonOpening opening,
            boolean maintenance,
            SeasonMode mode,
            int anchoredGrants,
            @Nullable Maintenance lastMaintenance,
            @Nullable Switch lastSwitch,
            @Nullable HandStart handStart) {}

    /** The last time maintenance was on, with {@code off} {@code null} while it still is. */
    public record Maintenance(Instant on, @Nullable Instant off, Actor.Kind byKind, String byId) {

        static Maintenance of(final MaintenanceSpan span) {
            return new Maintenance(
                    span.on(),
                    span.off(),
                    span.by().kind(),
                    Objects.requireNonNullElse(span.by().id(), ""));
        }
    }

    /** A mode switch with the mode it switches to and the run that does it. */
    public record Switch(SeasonMode target, @Nullable String reason, Updates.Run run) {}

    /** A start of the resting server through Steward, by whom and when it finished. */
    public record HandStart(String service, Actor.Kind byKind, String byId, Instant at) {}

    /**
     * The players online whom a change moves, for its dialog.
     *
     * @param members the players online who are no admins
     * @param withoutAccess the members among them without an active access period
     */
    public record Players(int members, int admins, int withoutAccess) {}

    public void summary(final Context ctx) {
        ctx.json(read());
    }

    public Router read() {
        final SeasonState state = data.season().state();
        final Optional<UpdateRequest> last = data.updates().lastSwitch();
        return new Router(
                state.launch(),
                state.opening(clock.instant()),
                state.maintenance(),
                state.mode(),
                data.season().anchoredGrants(),
                data.season().lastMaintenance().map(Maintenance::of).orElse(null),
                last.map(this::switchOf).orElse(null),
                handStart(state.mode().other().server(), last.orElse(null)));
    }

    private @Nullable Switch switchOf(final UpdateRequest run) {
        return data.updates()
                .requestOf(run.id())
                .filter(StewardRequest.SwitchMode.class::isInstance)
                .map(StewardRequest.SwitchMode.class::cast)
                .map(request -> new Switch(request.target(), request.reason(), Updates.describe(data.updates(), run)))
                .orElse(null);
    }

    /** Returns the newest finished start of {@code server} alone, if it came after {@code last} finished. */
    private @Nullable HandStart handStart(final String server, final @Nullable UpdateRequest last) {
        final @Nullable Instant since = last == null ? null : last.finished();
        for (final UpdateRequest run : data.updates().recent(HAND_START_RUNS)) {
            final Instant finished = run.finished();
            if (run.kind() != UpdateKind.START || run.status() != UpdateStatus.DONE || finished == null) {
                continue;
            }
            if (since != null && finished.isBefore(since)) {
                return null;
            }
            if (data.updates().scopeOf(run.id()).equals(List.of(server))) {
                return new HandStart(
                        server,
                        run.actor().kind(),
                        Objects.requireNonNullElse(run.actor().id(), ""),
                        finished);
            }
        }
        return null;
    }

    /** Counts the players a change moves. It reads each player's access, so only dialogs ask for it. */
    public void players(final Context ctx) {
        ctx.json(players(online.get()));
    }

    Players players(final ServicesApi.Online now) {
        final List<OnlinePlayer> everyone = now.roster().getOrDefault(NETWORK, List.of());
        final AccessReader access = data.access();
        final Set<UUID> admins = access.adminMinecraftAccounts();
        int adminCount = 0;
        int withoutAccess = 0;
        for (final OnlinePlayer player : everyone) {
            if (admins.contains(player.uuid())) {
                adminCount++;
            } else if (!access.accessState(player.uuid()).accessActive()) {
                withoutAccess++;
            }
        }
        return new Players(everyone.size() - adminCount, adminCount, withoutAccess);
    }

    /** Sets the launch date to a future instant. {@code SeasonDirectory} writes the journal entry. */
    public void launch(final Context ctx) {
        final Change ask = body(ctx);
        if (ask.at == null || ask.at.isBlank()) {
            throw new BadRequestResponse("at is the launch date as an ISO-8601 instant");
        }
        final Instant at;
        try {
            at = Instant.parse(ask.at.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestResponse(ask.at + " is not an ISO-8601 instant");
        }
        answer(ctx, data.season().setLaunch(at, actor(ctx), reason(ask)));
    }

    public void launchNow(final Context ctx) {
        final Change ask = body(ctx);
        answer(ctx, data.season().launchNow(actor(ctx), reason(ask)));
    }

    public void close(final Context ctx) {
        final Change ask = body(ctx);
        answer(ctx, data.season().closeSeason(actor(ctx), reason(ask)));
    }

    public void maintenance(final Context ctx) {
        final Change ask = body(ctx);
        if (ask.on == null) {
            throw new BadRequestResponse("on is true to start maintenance and false to end it");
        }
        answer(ctx, data.season().setMaintenance(ask.on, actor(ctx), reason(ask)));
    }

    /**
     * Asks for a mode switch run, refused while a switch waits or runs.
     * A switch to the active mode is refused too, unless the last switch failed and this is the try again.
     */
    public void switchMode(final Context ctx) {
        final Change ask = body(ctx);
        if (ask.target == null) {
            throw new BadRequestResponse("target is SMP or HUNGER_GAMES");
        }
        final SeasonMode target;
        try {
            target = SeasonMode.valueOf(ask.target.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse(ask.target + " is not a game mode");
        }
        final Optional<UpdateRequest> last = data.updates().lastSwitch();
        if (last.isPresent() && isOpen(last.get().status())) {
            throw new RequestRefused(409, ANSWER.switchOpen(), "SWITCH_OPEN", false);
        }
        final boolean failed = last.isPresent() && last.get().status() == UpdateStatus.FAILED;
        if (target == data.season().state().mode() && !failed) {
            throw new RequestRefused(409, ANSWER.switchToActive(target), "SWITCH_TO_ACTIVE", false);
        }
        final UpdateRequest written = data.updates()
                .submit(new StewardRequest.SwitchMode(target, reason(ask)), List.of(), actor(ctx), Duration.ZERO);
        final List<String> scope = data.updates().scopeOf(written.id());
        Journaling.line(ctx, new JournalDetail.RunAsked(written.id(), written.kind(), scope));
        ctx.status(202).json(new Switch(target, reason(ask), Updates.describe(written, scope, RunRecord.EMPTY)));
    }

    private static boolean isOpen(final UpdateStatus status) {
        return status == UpdateStatus.PENDING || status == UpdateStatus.RUNNING;
    }

    private static Change body(final Context ctx) {
        final Change ask = ctx.bodyAsClass(Change.class);
        if (ask == null) {
            throw new RequestRefused(400, ANSWER.empty());
        }
        return ask;
    }

    private Actor actor(final Context ctx) {
        return accounts.apply(ctx).actor();
    }

    private static @Nullable String reason(final Change ask) {
        return ask.reason == null || ask.reason.isBlank() ? null : ask.reason.trim();
    }

    private static void answer(final Context ctx, final SeasonChange change) {
        ctx.json(change);
    }

    /** The body of every change: the fields one change reads, and an optional reason for the journal. */
    static final class Change {
        @Nullable
        String at;

        @Nullable
        Boolean on;

        @Nullable
        String target;

        @Nullable
        String reason;
    }

    public void register(final JavalinConfig cfg) {
        cfg.routes.get("/api/season", this::summary, Gate.KEY_HELD);
        cfg.routes.get("/api/season/players", this::players, Gate.KEY_HELD);
        cfg.routes.post("/api/season/launch", this::launch, Gate.KEY_FRESH, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/season/launch-now", this::launchNow, Gate.KEY_FRESH, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/season/close", this::close, Gate.KEY_FRESH, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/season/maintenance", this::maintenance, Gate.KEY_HELD, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post(
                "/api/season/switch", this::switchMode, Gate.KEY_FRESH, Journaled.as(JournalAction.REQUEST_RUN));
    }
}
