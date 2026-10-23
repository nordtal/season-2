package eu.nordtal.season.steward.season;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.journal.Unjournaled;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** {@code /api/season}, {@code /api/season/phase} and {@code /api/season/date}. */
public final class SeasonRoutes {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private final Data data;
    private final Function<Context, DiscordAuth.Account> accounts;

    public SeasonRoutes(final Data data, final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    /** Sets the season phase. {@code PhaseDirectory} writes the journal entry. */
    public void phase(final Context ctx) {
        final SeasonChange ask = ctx.bodyAsClass(SeasonChange.class);
        if (ask == null || ask.phase == null) {
            throw new BadRequestResponse("phase is which phase to switch to");
        }
        final SeasonPhase phase;
        try {
            phase = SeasonPhase.valueOf(ask.phase.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse(ask.phase + " is not a phase");
        }
        final DiscordAuth.Account who = accounts.apply(ctx);
        final var change = data.phase().switchPhase(phase, who.actor(), ask.reason);
        ctx.json(change);
    }

    public void date(final Context ctx) {
        final SeasonChange ask = ctx.bodyAsClass(SeasonChange.class);
        if (ask == null) {
            throw new RequestRefused(400, ANSWER.empty());
        }
        final Instant at;
        if (ask.at == null) {
            at = null;
        } else if (ask.at.isBlank()) {
            throw new BadRequestResponse("at is the instant, as ISO-8601, or null to remove it");
        } else {
            try {
                at = Instant.parse(ask.at.trim());
            } catch (DateTimeParseException e) {
                throw new BadRequestResponse(ask.at + " is not an ISO-8601 instant");
            }
        }
        final DiscordAuth.Account who = accounts.apply(ctx);
        final Actor actor = who.actor();
        final var change = switch (ask.which == null ? "" : ask.which.trim()) {
            case "smpStart" -> data.phase().setSmpStart(at, actor);
            case "launch" -> data.phase().setLaunch(at, actor);
            default -> throw new BadRequestResponse("which is smpStart or launch");
        };
        ctx.json(change);
    }

    public void summary(final Context ctx) {
        ctx.json(read());
    }

    /** The answer to {@code GET /api/season}: the phase and each announced date. */
    public record Season(
            SeasonPhase phase,
            @Nullable Instant launch,
            @Nullable Instant smpStart) {}

    public Season read() {
        return new Season(
                data.phase().currentPhase(),
                data.phase().launch().orElse(null),
                data.phase().smpStart().orElse(null));
    }

    static final class SeasonChange {
        @Nullable
        String phase;

        @Nullable
        String reason;

        @Nullable
        String which;

        @Nullable
        String at;
    }

    public void register(final JavalinConfig cfg) {
        cfg.routes.post("/api/season/phase", this::phase, Gate.KEY_FRESH, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/season/date", this::date, Gate.KEY_HELD, Unjournaled.IN_THE_CHANGE);
        cfg.routes.get("/api/season", this::summary, Gate.KEY_HELD);
    }
}
