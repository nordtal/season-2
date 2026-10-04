package eu.nordtal.s2.smp.npc;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.context.MilestoneContext;
import eu.nordtal.s2.papercommon.game.GameKeys;
import eu.nordtal.s2.papercommon.menu.BlankItem;
import eu.nordtal.s2.papercommon.menu.Menu;
import eu.nordtal.s2.papercommon.menu.MenuClick;
import eu.nordtal.s2.papercommon.menu.SlotGeometry;
import eu.nordtal.s2.smp.board.ProgressBar;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneNames;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveRow;
import eu.nordtal.s2.smp.milestone.ObjectiveType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * What the spawn NPC opens: the current milestone's objectives, with the player's own share.
 *
 * Only a {@code HAND_IN} card opens the deposit screen, and a page turn opens a new inventory.
 */
public final class ObjectiveGui extends Menu {

    private final MessageRenderer renderer;
    private final Locale locale;
    private final Milestone milestone;
    private final List<ObjectiveRow> rows;
    private final OwnShare.Summary share;
    private final BiConsumer<Player, HandInGui> confirm;
    private final int page;
    private final Inventory inventory;

    /** The entries on this page, in card order. */
    private final List<Entry> entries = new ArrayList<>();

    /** One card: the definition, its stored progress, and which card of the page it is. */
    public record Entry(Objective objective, ObjectiveRow row, int card) {

        public boolean isHandIn() {
            return objective.type() == ObjectiveType.HAND_IN && !row.completed();
        }
    }

    public ObjectiveGui(
            final MessageRenderer renderer,
            final Locale locale,
            final Milestone milestone,
            final List<ObjectiveRow> rows,
            final OwnShare.Summary share,
            final BiConsumer<Player, HandInGui> confirm) {
        this(renderer, locale, milestone, rows, share, confirm, 0);
    }

    private ObjectiveGui(
            final MessageRenderer renderer,
            final Locale locale,
            final Milestone milestone,
            final List<ObjectiveRow> rows,
            final OwnShare.Summary share,
            final BiConsumer<Player, HandInGui> confirm,
            final int page) {
        this.renderer = renderer;
        this.locale = locale;
        this.milestone = milestone;
        this.rows = rows;
        this.share = share;
        this.confirm = confirm;
        this.page = Math.max(0, Math.min(page, pages() - 1));

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
                        renderer.format(locale, MESSAGES.smp().objectives().title()),
                        milestoneName(),
                        ProgressBar.of(finishedRatio(), ObjectivePanel.HEADING_BAR_WIDTH),
                        finished() + "/" + rows.size(),
                        cards,
                        shareLine(),
                        this.page > 0,
                        this.page < pages() - 1));
        fill();
    }

    /** A page button turns the page, a hand-in card opens the deposit screen, and any other card refuses. */
    @Override
    protected MenuClick click(final Player player, final int slot) {
        // The page buttons first; they sit on the share plate's own two cells, never the share line.
        if (isPrevious(slot) || isNext(slot)) {
            return MenuClick.opening(new ObjectiveGui(
                    renderer, locale, milestone, rows, share, confirm, page + (isPrevious(slot) ? -1 : 1)));
        }
        final Optional<Entry> entry = at(slot);
        if (entry.isEmpty()) {
            return MenuClick.nothing();
        }
        if (!entry.get().isHandIn()) {
            // A statistic counts itself, an advancement is earned elsewhere.
            return MenuClick.refused();
        }
        final ObjectiveRow row = entry.get().row();
        return MenuClick.opening(
                new HandInGui(renderer, locale, entry.get().objective(), row.amount(), row.target(), confirm));
    }

    /** Which entry a raw slot belongs to, if any. */
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
        return MilestoneNames.of(renderer.raw(), locale, milestone.key());
    }

    private String name(final Objective definition) {
        return definition.key();
    }

    /** The sentence on the bottom row, as the plain bundle value because {@code MenuFont} draws it. */
    private String shareLine() {
        if (share.empty()) {
            return renderer.raw().format(locale, MESSAGES.smp().objectives().shareNone());
        }
        return renderer.raw().format(locale, MESSAGES.smp().objectives().share(share.spins()));
    }

    private void fill() {
        final ItemStack heading = BlankItem.of(
                renderer.format(locale, MESSAGES.smp().objectives().heading(new MilestoneContext(milestoneName()))),
                List.of(renderer.format(locale, MESSAGES.smp().objectives().headingHint(finished(), rows.size()))));
        for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
            inventory.setItem(SlotGeometry.slot(column, ObjectivePanel.HEADING_ROW), heading);
        }

        for (final Entry entry : entries) {
            final ItemStack item = cardItem(entry);
            ObjectivePanel.slotsOf(entry.card()).forEach(slot -> inventory.setItem(slot, item));
        }

        final ItemStack shareItem =
                BlankItem.of(renderer.format(locale, MESSAGES.smp().objectives().shareTooltip()), shareLore());
        for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
            inventory.setItem(SlotGeometry.slot(column, ObjectivePanel.SHARE_ROW), shareItem);
        }

        // Last, so they win the two cells the share plate also covers.
        if (page > 0) {
            inventory.setItem(
                    ObjectivePanel.PREV_SLOT,
                    pageItem(MESSAGES.smp().objectives().previousPage()));
        }
        if (page < pages() - 1) {
            inventory.setItem(
                    ObjectivePanel.NEXT_SLOT,
                    pageItem(MESSAGES.smp().objectives().nextPage()));
        }
    }

    private ItemStack pageItem(final MessageRef label) {
        return BlankItem.of(renderer.format(locale, label), List.of());
    }

    private ItemStack cardItem(final Entry entry) {
        final ObjectiveRow row = entry.row();
        final Objective definition = entry.objective();

        final List<Component> lore = new ArrayList<>();
        lore.add(renderer.format(
                locale,
                MESSAGES.smp().objectives().progress(ProgressBar.of(row.ratio(), 20), row.amount(), row.target())));
        share.lines().stream()
                .filter(line -> line.key().equals(definition.key()))
                .findFirst()
                .ifPresent(line -> lore.add(
                        renderer.format(locale, MESSAGES.smp().objectives().yourShare(line.percent(), line.spins()))));
        if (row.completed()) {
            lore.add(renderer.format(locale, MESSAGES.smp().objectives().done()));
        } else if (definition.type() == ObjectiveType.HAND_IN) {
            lore.add(renderer.format(locale, MESSAGES.smp().objectives().clickToHandIn()));
            lore.add(renderer.format(
                    locale,
                    MESSAGES.smp()
                            .objectives()
                            .items((definition.items() == null ? List.<String>of() : definition.items())
                                    .stream().map(GameKeys::item).toList())));
        } else {
            lore.add(renderer.format(locale, MESSAGES.smp().objectives().countsItself()));
        }

        // The objective's name goes in as a parameter so MessageRenderer escapes it; the colour is the bundle's.
        return BlankItem.of(
                renderer.format(
                        locale,
                        row.completed()
                                ? MESSAGES.smp().objectives().itemDone(name(definition))
                                : MESSAGES.smp().objectives().item(name(definition))),
                lore);
    }

    private List<Component> shareLore() {
        final List<Component> lore = new ArrayList<>();
        if (share.empty()) {
            lore.add(renderer.format(locale, MESSAGES.smp().objectives().shareEmptyHint()));
            return lore;
        }
        for (final OwnShare.Line line : share.lines()) {
            final Objective definition = milestone.objective(line.key()).orElse(null);
            if (definition == null) {
                continue;
            }
            lore.add(renderer.format(locale, MESSAGES.smp().objectives().shareLine(name(definition), line.percent())));
        }
        lore.add(renderer.format(locale, MESSAGES.smp().objectives().shareSpins(share.spins())));
        return lore;
    }
}
