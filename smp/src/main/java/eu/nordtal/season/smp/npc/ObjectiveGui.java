package eu.nordtal.season.smp.npc;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import eu.nordtal.season.smp.board.ProgressBar;
import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneNames;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.milestone.ObjectiveType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** The objective menu of the spawn NPC. It lists the objectives of one milestone and the player's share. */
public final class ObjectiveGui extends Menu {

    /**
     * @param spins the extra spins the player's shares would give now
     * @param budget the extra spins of all objectives
     */
    record Window(
            MilestoneContext milestone,
            @Example("2") long done,
            @Example("8") long total,
            @Example("3") long spins,
            @Example("10") long budget,
            boolean contributed) {}

    /**
     * @param handIn whether a click opens the hand-in window
     * @param counted whether the server counts it, unfinished
     * @param gives the extra spins the player's share would give now
     * @param pool the extra spins of the objective
     */
    record Card(
            @Example("Mine 64 diamonds") String objective,
            boolean finished,
            boolean handIn,
            boolean counted,
            @Example("████████████░░░░░░░░") String bar,
            @Example("40") long amount,
            @Example("64") long target,
            boolean shared,
            @Example("62.5") double percent,
            @Example("3") long gives,
            @Example("12") long pool) {}

    record Share(
            @Example("Mine 64 diamonds") String objective,
            @Example("62.5") double percent) {}

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
            "smp",
            "objectives",
            "Menus",
            Window.class,
            Slot.item("heading"),
            Slot.item("objective", Card.class),
            Slot.list("wanted", HandInGui.Wanted.class),
            Slot.item("share"),
            Slot.list("shares", Share.class),
            Slot.label("share-line"),
            Slot.label("share-none"),
            Slot.item("previous-page"),
            Slot.item("next-page"));

    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final Locale locale;
    private final Milestone milestone;
    private final List<ObjectiveRow> rows;
    private final OwnShare.Summary share;
    private final BiConsumer<Player, HandInGui> confirm;
    private final int page;
    private final GameDocument document;
    private final Inventory inventory;

    private final List<Entry> entries = new ArrayList<>();

    /** An objective with its stored row and its card index on the current page. */
    public record Entry(Objective objective, ObjectiveRow row, int card) {

        public boolean isHandIn() {
            return objective.type() == ObjectiveType.HAND_IN && !row.completed();
        }
    }

    public ObjectiveGui(
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Locale locale,
            final Milestone milestone,
            final List<ObjectiveRow> rows,
            final OwnShare.Summary share,
            final BiConsumer<Player, HandInGui> confirm) {
        this(renderer, documents, locale, milestone, rows, share, confirm, 0);
    }

    private ObjectiveGui(
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Locale locale,
            final Milestone milestone,
            final List<ObjectiveRow> rows,
            final OwnShare.Summary share,
            final BiConsumer<Player, HandInGui> confirm,
            final int page) {
        this.renderer = renderer;
        this.documents = documents;
        this.locale = locale;
        this.milestone = milestone;
        this.rows = rows;
        this.share = share;
        this.confirm = confirm;
        this.page = Math.max(0, Math.min(page, pages() - 1));
        this.document = new GameDocument(
                renderer,
                documents.applied(DOCUMENT),
                Viewer.of(locale),
                new Window(
                        new MilestoneContext(milestoneName()),
                        finished(),
                        rows.size(),
                        share.spins(),
                        share.budget(),
                        !share.empty()));

        final List<ObjectiveRow> shown = slice();
        final List<ObjectivePanel.Card> cards = new ArrayList<>(shown.size());
        for (int index = 0; index < shown.size(); index++) {
            final ObjectiveRow row = shown.get(index);
            final Objective definition = milestone.objective(row.key()).orElse(null);
            if (definition == null) {
                continue;
            }
            entries.add(new Entry(definition, row, cards.size()));
            cards.add(new ObjectivePanel.Card(
                    ObjectivePanel.icon(definition.type(), row.completed()),
                    name(definition),
                    row.amount() + "/" + row.target(),
                    row.ratio(),
                    row.completed()));
        }

        this.inventory = frame(
                ObjectivePanel.ROWS,
                ObjectivePanel.title(
                        document.title(),
                        milestoneName(),
                        ProgressBar.of(finishedRatio(), ObjectivePanel.HEADING_BAR_WIDTH),
                        finished() + "/" + rows.size(),
                        cards,
                        shareLine(),
                        this.page > 0,
                        this.page < pages() - 1));
        fill();
    }

    /** Opens another page for a page button and a {@link HandInGui} for a {@code HAND_IN} card. Refuses other cards. */
    @Override
    protected MenuClick click(final Player player, final int slot) {
        // The page buttons share their slots with the share plate and take precedence.
        if (isPrevious(slot) || isNext(slot)) {
            return MenuClick.opening(new ObjectiveGui(
                    renderer, documents, locale, milestone, rows, share, confirm, page + (isPrevious(slot) ? -1 : 1)));
        }
        final Optional<Entry> entry = at(slot);
        if (entry.isEmpty()) {
            return MenuClick.nothing();
        }
        if (!entry.get().isHandIn()) {
            return MenuClick.refused();
        }
        final ObjectiveRow row = entry.get().row();
        return MenuClick.opening(new HandInGui(
                renderer, documents, locale, entry.get().objective(), row.amount(), row.target(), confirm));
    }

    private Optional<Entry> at(final int slot) {
        final int card = ObjectivePanel.cardOf(slot);
        return card < 0
                ? Optional.empty()
                : entries.stream().filter(entry -> entry.card() == card).findFirst();
    }

    private boolean isPrevious(final int slot) {
        return slot == ObjectivePanel.PREV_SLOT && page > 0;
    }

    private boolean isNext(final int slot) {
        return slot == ObjectivePanel.NEXT_SLOT && page < pages() - 1;
    }

    private int pages() {
        return Math.max(1, (rows.size() + ObjectivePanel.CARDS_PER_PAGE - 1) / ObjectivePanel.CARDS_PER_PAGE);
    }

    private List<ObjectiveRow> slice() {
        final int from = page * ObjectivePanel.CARDS_PER_PAGE;
        return rows.subList(Math.min(from, rows.size()), Math.min(from + ObjectivePanel.CARDS_PER_PAGE, rows.size()));
    }

    private long finished() {
        return rows.stream().filter(ObjectiveRow::completed).count();
    }

    private double finishedRatio() {
        return rows.isEmpty() ? 0.0 : (double) finished() / (double) rows.size();
    }

    private String milestoneName() {
        return MilestoneNames.of(renderer, locale, milestone.key());
    }

    private String name(final Objective definition) {
        return definition.key();
    }

    /** Returns the share sentence as plain text, which {@code MenuFont} draws. */
    private String shareLine() {
        return document.label(share.empty() ? "share-none" : "share-line");
    }

    private void fill() {
        final ItemStack heading = BlankItem.of(document.item("heading"));
        for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
            inventory.setItem(SlotGeometry.slot(column, ObjectivePanel.HEADING_ROW), heading);
        }

        for (final Entry entry : entries) {
            final ItemStack item = cardItem(entry);
            ObjectivePanel.slotsOf(entry.card()).forEach(slot -> inventory.setItem(slot, item));
        }

        final List<Share> shares = new ArrayList<>();
        for (final OwnShare.Line line : share.lines()) {
            milestone
                    .objective(line.key())
                    .ifPresent(definition -> shares.add(new Share(name(definition), line.percent())));
        }
        final ItemStack shareItem = BlankItem.of(document.item("share", null, Map.of("shares", shares)));
        for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
            inventory.setItem(SlotGeometry.slot(column, ObjectivePanel.SHARE_ROW), shareItem);
        }

        // The page buttons are set last to overwrite the share plate slots.
        if (page > 0) {
            inventory.setItem(ObjectivePanel.PREV_SLOT, BlankItem.of(document.item("previous-page")));
        }
        if (page < pages() - 1) {
            inventory.setItem(ObjectivePanel.NEXT_SLOT, BlankItem.of(document.item("next-page")));
        }
    }

    private ItemStack cardItem(final Entry entry) {
        final ObjectiveRow row = entry.row();
        final Objective definition = entry.objective();
        final Optional<OwnShare.Line> own = share.lines().stream()
                .filter(line -> line.key().equals(definition.key()))
                .findFirst();
        final boolean handIn = !row.completed() && definition.type() == ObjectiveType.HAND_IN;
        // The name is a value, which MessageRenderer escapes.
        return BlankItem.of(document.item(
                "objective",
                new Card(
                        name(definition),
                        row.completed(),
                        handIn,
                        !row.completed() && !handIn,
                        ProgressBar.of(row.ratio(), 20),
                        row.amount(),
                        row.target(),
                        own.isPresent(),
                        own.map(OwnShare.Line::percent).orElse(0.0),
                        own.map(OwnShare.Line::spins).orElse(0),
                        own.map(OwnShare.Line::budget).orElse(0)),
                Map.of(
                        "wanted",
                        handIn && definition.items() != null ? HandInGui.wanted(definition.items()) : List.of())));
    }
}
