package eu.nordtal.season.smp.npc;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.ui.BodyColumn;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.packrendering.ui.Tooltip;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.dialog.Paging;
import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneNames;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.milestone.ObjectiveType;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The spawn NPC's dialog: the objectives of the running milestone as cards, three a page, and the player's share.
 * A click on an open hand-in card opens its deposit window; a counted card only says that it counts itself.
 */
public final class ObjectiveDialog {

    record Summary(@Example("3") long done, @Example("7") long total) {}

    record Count(
            @Example("12400") long amount, @Example("20000") long target) {}

    record Counts(@Example("Iron Ingot, Block of Iron") String items) {}

    record Part(@Example("20") double percent) {}

    record HandInWords(
            @Example("Iron Ingot, Block of Iron") String items,
            @Example("20") double percent) {}

    record ShareLine(
            @Example("3") long spins, @Example("20") long budget) {}

    record Share(
            @Example("Iron ingots") String objective,
            @Example("20") double percent) {}

    static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "objectives",
                    "Menus",
                    DocumentSpec.None.class,
                    1,
                    Slot.label("summary", Summary.class),
                    Slot.label("count", Count.class),
                    Slot.label("finished"),
                    Slot.label("counts", Counts.class),
                    Slot.label("part", Part.class),
                    Slot.label("hand-in-hint"),
                    Slot.label("counted"),
                    Slot.label("hand-in-words", Counts.class),
                    Slot.label("hand-in-words-shared", HandInWords.class),
                    Slot.label("counted-words"),
                    Slot.label("share", ShareLine.class),
                    Slot.label("share-none"),
                    Slot.label("share-title"),
                    Slot.label("share-row", Share.class),
                    Slot.label("not-started"),
                    Slot.label("none"),
                    Slot.label("all"),
                    Slot.label("pager", Paging.Page.class))
            .titled("Milestone");

    static final int EACH = 3;

    /** The content: the widest card, and the share line with its symbol and the widest pager. */
    static final int WIDTH = 248;

    private static final int TEXT_TOP = 50;

    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final Dialogs dialogs;
    private final Locale locale;
    private final Milestone milestone;
    private final List<ObjectiveRow> rows;
    private final OwnShare.Summary share;
    private final BiConsumer<Player, HandInGui> confirm;
    private final GameDocument document;

    public ObjectiveDialog(
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Dialogs dialogs,
            final Locale locale,
            final Milestone milestone,
            final List<ObjectiveRow> rows,
            final OwnShare.Summary share,
            final BiConsumer<Player, HandInGui> confirm) {
        this.renderer = renderer;
        this.documents = documents;
        this.dialogs = dialogs;
        this.locale = locale;
        this.milestone = milestone;
        this.rows = List.copyOf(rows);
        this.share = share;
        this.confirm = confirm;
        this.document = document(renderer, documents, locale);
    }

    private static GameDocument document(
            final MessageRenderer renderer, final AppliedDocuments documents, final Locale locale) {
        return new GameDocument(renderer, documents.applied(DOCUMENT), Viewer.of(locale), new DocumentSpec.None());
    }

    /** Why the menu has no objectives to show. */
    public enum Empty {
        /** The first milestone, which has no objectives, is not unlocked yet. */
        NOT_STARTED("not-started"),
        /** No milestone is active, though some are left. */
        NONE("none"),
        /** Every milestone is finished. */
        ALL("all");

        private final String label;

        Empty(final String label) {
            this.label = label;
        }

        /**
         * Returns why the menu shows no objectives, or nothing when {@code active} has objectives to show.
         * A milestone without objectives waits for an admin unlock. The first one's unlock starts the season.
         */
        public static Optional<Empty> of(
                final MilestoneTrack track, final List<String> completed, final @Nullable Milestone active) {
            if (active == null) {
                if (track.size() > 0 && track.next(completed).isEmpty()) {
                    return Optional.of(ALL);
                }
                return Optional.of(completed.isEmpty() ? NOT_STARTED : NONE);
            }
            if (active.objectives().isEmpty()) {
                return Optional.of(completed.isEmpty() ? NOT_STARTED : NONE);
            }
            return Optional.empty();
        }
    }

    /** Shows that no objectives run, and why. */
    public static Dialog empty(
            final MessageRenderer renderer, final AppliedDocuments documents, final Locale locale, final Empty why) {
        final GameDocument document = document(renderer, documents, locale);
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title(document, DialogPanel.OBJECTIVES_33, TEXT_TOP))
                        .body(List.of(Dialogs.line(
                                BodyLine.textLine()
                                        .text(document.label(why.label), Colours.SOFT)
                                        .build(),
                                WIDTH)))
                        .build())
                .type(Dialogs.bodyOnly()));
    }

    private static Component title(final GameDocument document, final DialogPanel panel, final int top) {
        return DialogTitle.of(panel, top)
                .header(Symbol.MEDAL, Dialogs.plain(document.title()), List.of())
                .build();
    }

    public void show(final Player player, final int page) {
        player.showDialog(dialog(page));
    }

    private Dialog dialog(final int page) {
        final Dialogs.Send send = dialogs.send();
        final Paging paging = Paging.of(rows.size(), EACH);
        final List<Card> cards = new ArrayList<>();
        for (final ObjectiveRow row : paging.slice(rows, page)) {
            milestone.objective(row.key()).ifPresent(objective -> cards.add(new Card(objective, row)));
        }
        final BodyColumn column = BodyColumn.of(WIDTH, true)
                .text(this::summary)
                .gap(BodyColumn.UNDER_TEXT - BodyColumn.TEXT)
                .rows(cards.size(), EACH, index -> line -> card(line, cards.get(index), send));
        final List<DialogBody> body =
                List.of(Dialogs.line(column.build(), WIDTH), Dialogs.line(shareLine(paging, page, send), WIDTH));
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title(document, DialogPanel.OBJECTIVES_124, column.panelTop()))
                        .body(body)
                        .build())
                .type(Dialogs.bodyOnly()));
    }

    private record Card(Objective objective, ObjectiveRow row) {}

    /** The milestone's name at the left and how many objectives are finished at the right. */
    private void summary(final BodyLine line) {
        final long done = rows.stream().filter(ObjectiveRow::completed).count();
        final String count = document.label("summary", new Summary(done, rows.size()));
        final int countX = WIDTH - Letters.visibleWidth(count);
        final String name = MilestoneNames.of(renderer, locale, milestone.key());
        line.text(Letters.cut(name, countX - BodyLine.PAD - BodyLine.PAD), Colours.INK)
                .at(countX)
                .text(count, Colours.SOFT);
    }

    private void card(final BodyLine line, final Card card, final Dialogs.Send send) {
        final Objective objective = card.objective();
        final ObjectiveRow row = card.row();
        final String name = MilestoneNames.objective(renderer, locale, milestone.key(), objective.key());
        final boolean done = row.completed();
        final String count =
                done ? document.label("finished") : document.label("count", new Count(row.amount(), row.target()));
        final Optional<OwnShare.Line> own = share.lines().stream()
                .filter(shared -> shared.key().equals(objective.key()))
                .findFirst();
        final Symbol symbol = symbol(objective);
        if (done) {
            line.card(WIDTH, symbol, name, count, row.ratio(), true, Look.NORMAL);
            return;
        }
        final BodyLine.Press press;
        final String words;
        if (objective.type() == ObjectiveType.HAND_IN) {
            // The server holds vanilla's English item names only; the client translates nothing in a drawn tooltip.
            final String items = HandInGui.wanted(objective.items()).stream()
                    .map(wanted -> wanted.item().english())
                    .collect(Collectors.joining(", "));
            final String counts = document.label("counts", new Counts(items));
            final String text = own.map(entry -> counts + "\n" + document.label("part", new Part(entry.percent())))
                    .orElse(counts);
            press = new BodyLine.Press(
                    send.click(player -> openHandIn(player, objective, row)),
                    Tooltip.hover(name, text, document.label("hand-in-hint")));
            words = own.map(entry -> document.label("hand-in-words-shared", new HandInWords(items, entry.percent())))
                    .orElse(document.label("hand-in-words", new Counts(items)));
        } else {
            press = new BodyLine.Press(null, Tooltip.hover(name, document.label("counted"), null));
            words = document.label("counted-words");
        }
        line.button(
                press,
                part -> part.card(WIDTH, symbol, name, count, row.ratio(), false, Look.NORMAL)
                        .words(words));
    }

    /** A statistic that counts kills shows the swords, any other the pickaxe; an advancement shows its flag. */
    static Symbol symbol(final Objective objective) {
        return switch (objective.type()) {
            case HAND_IN -> Symbol.HANDIN;
            case ADVANCEMENT -> Symbol.FLAG;
            case STATISTIC ->
                objective.statistic().toLowerCase(Locale.ROOT).contains("kill") ? Symbol.SWORDS : Symbol.PICKAXE;
        };
    }

    private void openHandIn(final Player player, final Objective objective, final ObjectiveRow row) {
        new HandInGui(renderer, documents, locale, objective, row.amount(), row.target(), confirm).open(player);
    }

    /** Fits the share into {@code room} beside its symbol. */
    static String shareText(final String text, final int room) {
        return Letters.cut(text, room);
    }

    /** The wheel and the player's share, which shows what they put in, and the pager at the right end. */
    private Component shareLine(final Paging paging, final int page, final Dialogs.Send send) {
        final boolean none = share.empty();
        final String text = none
                ? document.label("share-none")
                : document.label("share", new ShareLine(share.spins(), share.budget()));
        final @Nullable Component tooltip = none ? null : shareTooltip();
        final int room = paging.paged() ? WIDTH - Dialogs.pagerWidth(paging, page) - Symbol.BEFORE_TEXT : WIDTH;
        final String shown = shareText(text, room);
        final BodyLine line = BodyColumn.next()
                .button(
                        new BodyLine.Press(null, tooltip),
                        part -> part.symbol(Symbol.WHEEL, none ? Colours.SOFT : Colours.ACCENT)
                                .text(shown, Colours.SOFT));
        if (paging.paged()) {
            line.at(WIDTH - Dialogs.pagerWidth(paging, page));
            send.pager(line, paging, page, document.label("pager", paging.page(page)), this::show);
        }
        return line.at(WIDTH).build();
    }

    private Component shareTooltip() {
        final List<Tooltip.Row> tooltip = new ArrayList<>();
        tooltip.add(new Tooltip.Row(document.label("share-title"), Colours.TOOLTIP_TITLE));
        for (final OwnShare.Line own : share.lines()) {
            if (tooltip.size() == Tooltip.MAX_ROWS) {
                break;
            }
            milestone
                    .objective(own.key())
                    .ifPresent(objective -> tooltip.add(new Tooltip.Row(
                            document.label(
                                    "share-row",
                                    new Share(
                                            MilestoneNames.objective(
                                                    renderer, locale, milestone.key(), objective.key()),
                                            own.percent())),
                            Colours.TOOLTIP_TEXT)));
        }
        return Tooltip.hover(tooltip, 1);
    }
}
