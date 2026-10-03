package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.data.Data;
import eu.nordtal.s2.steward.texts.RequestRefused;
import eu.nordtal.s2.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** {@code /api/season}, {@code /api/season/phase} and {@code /api/season/date}. */
final class SeasonRoutes {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private final @Nullable Data data;
    private final Function<Context, DiscordAuth.Account> accounts;

    SeasonRoutes(final @Nullable Data data, final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    private Data data() {
        return Objects.requireNonNull(data, "this route needs the database, which this instance has none of");
    }

    /** Switches the season phase; {@code PhaseDirectory} writes the {@code audit_log} row itself. */
    void phase(final Context ctx) {
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
        final var change = data().phase().switchPhase(phase, who.actor(), ask.reason);
        ctx.json(change);
    }

    void date(final Context ctx) {
        final SeasonChange ask = ctx.bodyAsClass(SeasonChange.class);
        if (ask == null) {
            throw new RequestRefused(400, ANSWER.empty());
        }
        // A null `at` is "no date"; a blank string is a field somebody forgot to fill.
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
        // An unmatched value must refuse rather than default to one date.
        final var change = switch (ask.which == null ? "" : ask.which.trim()) {
            case "smpStart" -> data().phase().setSmpStart(at, actor);
            case "launch" -> data().phase().setLaunch(at, actor);
            default -> throw new BadRequestResponse("which is smpStart or launch");
        };
        ctx.json(change);
    }

    void summary(final Context ctx) {
        ctx.json(read());
    }

    /** {@code GET /api/season}: the phase, and each date once it is announced. */
    public record Season(
            SeasonPhase phase,
            @Nullable Instant launch,
            @Nullable Instant smpStart) {}

    /** The phase and the two dates, as {@code GET /api/season} answers. */
    Season read() {
        return new Season(
                data().phase().currentPhase(),
                data().phase().launch().orElse(null),
                data().phase().smpStart().orElse(null));
    }

    /** The body of both season endpoints; each reads the fields it needs. */
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
}
