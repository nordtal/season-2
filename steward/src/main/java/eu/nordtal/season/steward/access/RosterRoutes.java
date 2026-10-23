package eu.nordtal.season.steward.access;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.journal.JournalSearch;
import eu.nordtal.season.database.journal.JournalSearch.Term;
import eu.nordtal.season.database.journal.JournalStream;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.steward.QueryLimit;
import eu.nordtal.season.steward.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The routes for the roster, the payments, the access and the journal. */
public final class RosterRoutes {

    private final @Nullable Data data;
    private final Messages texts;

    public RosterRoutes(final @Nullable Data data, final Messages texts) {
        this.data = data;
        this.texts = texts;
    }

    private Data data() {
        return Objects.requireNonNull(data, "this route needs the database, which this instance has none of");
    }

    public void people(final Context ctx) {
        ctx.json(data().access().people(QueryLimit.of(ctx, 500, 2000)));
    }

    public void grants(final Context ctx) {
        ctx.json(data().access().grantsOf(DiscordId.of(ctx.pathParam("id"))));
    }

    /** Answers the payment requests of one person, newest first, up to a limit. */
    public void paymentsOf(final Context ctx) {
        ctx.json(data().payments().recentOf(DiscordId.of(ctx.pathParam("id")), QueryLimit.of(ctx, 200, 1000)));
    }

    public void payments(final Context ctx) {
        ctx.json(data().payments().recent(QueryLimit.of(ctx, 200, 1000)));
    }

    /** Answers every open payment request, oldest first, without a limit. */
    public void openPayments(final Context ctx) {
        ctx.json(data().payments().allOpen());
    }

    /**
     * Answers a page of the journal's stream, narrowed by every word of {@code q}, after the cursor {@code after}.
     * Action words are read against the labels in the default language.
     */
    public void journal(final Context ctx) {
        final Map<JournalAction, String> labels = new EnumMap<>(JournalAction.class);
        for (final JournalAction action : JournalAction.values()) {
            labels.put(action, texts.format(Locales.DEFAULT, TEXTS.journal().action(action)));
        }
        final List<Term> terms =
                JournalSearch.parse(ctx.queryParam("q"), data().journal().services(), labels);
        final JournalStream.Page page;
        try {
            page = data().journal().page(terms, ctx.queryParam("after"), QueryLimit.of(ctx, 50, 200));
        } catch (final IllegalArgumentException | DateTimeParseException refused) {
            throw new BadRequestResponse("after is the next cursor of a page of the journal");
        }
        ctx.json(JournalPage.of(page, terms));
    }
}
