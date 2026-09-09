package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.papercommon.menu.BlankItem;
import eu.nordtal.s2.smp.board.ProgressBar;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveType;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the spawn NPC opens: the current milestone's objectives, each with what is needed and how
 * much is already there, and what the player themselves has put in.
 *
 * <h2>What it looks like</h2>
 * The window is design {@code O3} (owner, 2026-09-08), drawn by {@link ObjectivePanel}: a heading
 * plate naming the milestone, four painted cards, and a share line along the bottom. Every slot
 * under the paint holds a {@link BlankItem}, because a painted card with no item in it is a card
 * nobody can hover or click - and the tooltip is where the exact numbers, the wanted items and the
 * per-objective share live, since a 68-pixel card holds a name and nothing else.
 *
 * <p>A {@code HAND_IN} objective is clickable and opens the deposit screen; the other two types are
 * shown and are not, because there is nothing to click - a statistic counts itself and an
 * advancement is earned somewhere else entirely.
 *
 * <h2>A page is a new inventory</h2>
 * Same as {@code NavigateGui}, and for the same reason: a chest's title is fixed when it is opened,
 * so a surface that changes is a second inventory. No database work happens on a page turn - the
 * whole milestone was read once when the NPC was clicked.
 */
public final class ObjectiveGui implements Surface {

    private final Messages messages;
    private final Locale locale;
    private final Milestone milestone;
    private final List<ObjectiveRow> rows;
    private final OwnShare.Summary share;
    private final int page;
    private final Inventory inventory;

    /** The entries on <em>this</em> page, in card order. */
    private final List<Entry> entries = new ArrayList<>();

    /** One card: the definition, its stored progress, and which card of the page it is. */
    public record Entry(Objective objective, ObjectiveRow row, int card) {

        public boolean isHandIn() {
            return objective.type() == ObjectiveType.HAND_IN && !row.completed();
        }
    }

    public ObjectiveGui(final Messages messages, final Locale locale, final Milestone milestone,
                        final List<ObjectiveRow> rows, final OwnShare.Summary share) {
        this(messages, locale, milestone, rows, share, 0);
    }

    private ObjectiveGui(final Messages messages, final Locale locale, final Milestone milestone,
                         final List<ObjectiveRow> rows, final OwnShare.Summary share,
                         final int page) {
        this.messages = messages;
        this.locale = locale;
        this.milestone = milestone;
        this.rows = rows;
        this.share = share;
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

        this.inventory = Bukkit.createInventory(this,
                ObjectivePanel.ROWS * SlotGeometry.COLUMNS,
                ObjectivePanel.title(
                        MessageRenderer.of(messages).get(locale, "smp.objectives.title"),
                        milestoneName(),
                        ProgressBar.of(finishedRatio(), ObjectivePanel.HEADING_BAR_WIDTH),
                        finished() + "/" + rows.size(),
                        cards,
                        shareLine(),
                        this.page > 0,
                        this.page < pages() - 1));
        fill();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** The same milestone on another page - what a page button opens. */
    public ObjectiveGui onPage(final int wanted) {
        return new ObjectiveGui(messages, locale, milestone, rows, share, wanted);
    }

    public boolean hasPage(final int wanted) {
        return wanted >= 0 && wanted < pages();
    }

    public int page() {
        return page;
    }

    /** Which entry a raw slot belongs to, if any. */
    public Optional<Entry> at(final int slot) {
        final int card = ObjectivePanel.cardOf(slot);
        return card < 0 ? Optional.empty()
                : entries.stream().filter(entry -> entry.card() == card).findFirst();
    }

    public boolean isPrevious(final int slot) {
        return slot == ObjectivePanel.PREV_SLOT && page > 0;
    }

    public boolean isNext(final int slot) {
        return slot == ObjectivePanel.NEXT_SLOT && page < pages() - 1;
    }

    // --- what is drawn -------------------------------------------------------------------

    private int pages() {
        return Math.max(1, (rows.size() + ObjectivePanel.CARDS_PER_PAGE - 1)
                / ObjectivePanel.CARDS_PER_PAGE);
    }

    private List<ObjectiveRow> slice() {
        final int from = page * ObjectivePanel.CARDS_PER_PAGE;
        return rows.subList(Math.min(from, rows.size()),
                Math.min(from + ObjectivePanel.CARDS_PER_PAGE, rows.size()));
    }

    private long finished() {
        return rows.stream().filter(ObjectiveRow::completed).count();
    }

    private double finishedRatio() {
        return rows.isEmpty() ? 0.0 : (double) finished() / (double) rows.size();
    }

    private String milestoneName() {
        final String key = "smp.milestone." + milestone.key();
        return messages.hasTranslation(locale, key) ? messages.get(locale, key) : milestone.key();
    }

    private String name(final Objective definition) {
        final String key = "smp.objective." + milestone.key() + "." + definition.key();
        return messages.hasTranslation(locale, key) ? messages.get(locale, key) : definition.key();
    }

    /**
     * The sentence on the bottom row.
     *
     * <p>Drawn by {@link eu.nordtal.s2.common.menu.MenuFont}, so it is the plain bundle value and
     * never a rendered component: MiniMessage in either of these two keys would be printed
     * character for character, and the five-pixel sheet has no angle brackets.</p>
     */
    private String shareLine() {
        if (share.empty()) {
            return messages.get(locale, "smp.objectives.share-none");
        }
        return messages.format(locale, "smp.objectives.share",
                Map.of("percent", OwnShare.format(share.percent(), locale),
                        "spins", String.valueOf(share.spins())));
    }

    private void fill() {
        final ItemStack heading = BlankItem.of(
                MessageRenderer.of(messages).format(locale, "smp.objectives.heading",
                        "milestone", milestoneName()),
                List.of(MessageRenderer.of(messages).format(locale, "smp.objectives.heading-hint",
                        "done", finished(), "total", rows.size())));
        for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
            inventory.setItem(SlotGeometry.slot(column, ObjectivePanel.HEADING_ROW), heading);
        }

        for (final Entry entry : entries) {
            final ItemStack item = cardItem(entry);
            ObjectivePanel.slotsOf(entry.card()).forEach(slot -> inventory.setItem(slot, item));
        }

        final ItemStack shareItem = BlankItem.of(
                MessageRenderer.of(messages).get(locale, "smp.objectives.share-tooltip"),
                shareLore());
        for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
            inventory.setItem(SlotGeometry.slot(column, ObjectivePanel.SHARE_ROW), shareItem);
        }

        // Last, so they win the two cells the share plate also covers.
        if (page > 0) {
            inventory.setItem(ObjectivePanel.PREV_SLOT, pageItem("smp.objectives.previous-page"));
        }
        if (page < pages() - 1) {
            inventory.setItem(ObjectivePanel.NEXT_SLOT, pageItem("smp.objectives.next-page"));
        }
    }

    private ItemStack pageItem(final String key) {
        return BlankItem.of(MessageRenderer.of(messages).get(locale, key), List.of());
    }

    private ItemStack cardItem(final Entry entry) {
        final MessageRenderer renderer = MessageRenderer.of(messages);
        final ObjectiveRow row = entry.row();
        final Objective definition = entry.objective();

        final List<Component> lore = new ArrayList<>();
        lore.add(renderer.format(locale, "smp.objectives.progress",
                "bar", ProgressBar.of(row.ratio(), 20),
                "amount", row.amount(), "target", row.target()));
        share.lines().stream()
                .filter(line -> line.key().equals(definition.key()))
                .findFirst()
                .ifPresent(line -> lore.add(renderer.format(locale, "smp.objectives.your-share",
                        "percent", OwnShare.format(line.percent(), locale),
                        "spins", line.spins())));
        if (row.completed()) {
            lore.add(renderer.get(locale, "smp.objectives.done"));
        } else if (definition.type() == ObjectiveType.HAND_IN) {
            lore.add(renderer.get(locale, "smp.objectives.click-to-hand-in"));
            lore.add(renderer.format(locale, "smp.objectives.items", "items",
                    String.join(", ",
                            definition.items() == null ? List.of() : definition.items())));
        } else {
            lore.add(renderer.get(locale, "smp.objectives.counts-itself"));
        }

        // The objective's name goes in as a parameter so MessageRenderer escapes it; the colour is
        // the bundle's (finding 48).
        return BlankItem.of(renderer.format(locale,
                row.completed() ? "smp.objectives.item-done" : "smp.objectives.item",
                "objective", name(definition)), lore);
    }

    private List<Component> shareLore() {
        final MessageRenderer renderer = MessageRenderer.of(messages);
        final List<Component> lore = new ArrayList<>();
        if (share.empty()) {
            lore.add(renderer.get(locale, "smp.objectives.share-empty-hint"));
            return lore;
        }
        for (final OwnShare.Line line : share.lines()) {
            final Objective definition = milestone.objective(line.key()).orElse(null);
            if (definition == null) {
                continue;
            }
            lore.add(renderer.format(locale, "smp.objectives.share-line",
                    "objective", name(definition),
                    "percent", OwnShare.format(line.percent(), locale)));
        }
        lore.add(renderer.format(locale, "smp.objectives.share-spins", "spins", share.spins()));
        return lore;
    }
}
