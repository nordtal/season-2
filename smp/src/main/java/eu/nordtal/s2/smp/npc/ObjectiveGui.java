package eu.nordtal.s2.smp.npc;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.MilestoneContext;
import eu.nordtal.s2.smp.board.ProgressBar;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.menu.BlankItem;
import eu.nordtal.s2.smp.menu.SlotGeometry;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneNames;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * What the spawn NPC opens: the current milestone's objectives, with the player's own share.
 *
 * Only a {@code HAND_IN} card opens the deposit screen, and a page turn opens a new inventory.
 */
public final class ObjectiveGui implements Surface {

    private final Messages messages;
    private final Locale locale;
    private final Milestone milestone;
    private final List<ObjectiveRow> rows;
    private final OwnShare.Summary share;
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
            final Messages messages,
            final Locale locale,
            final Milestone milestone,
            final List<ObjectiveRow> rows,
            final OwnShare.Summary share) {
        this(messages, locale, milestone, rows, share, 0);
    }

    private ObjectiveGui(
            final Messages messages,
            final Locale locale,
            final Milestone milestone,
            final List<ObjectiveRow> rows,
            final OwnShare.Summary share,
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

        this.inventory = Bukkit.createInventory(
                this,
                ObjectivePanel.ROWS * SlotGeometry.COLUMNS,
                ObjectivePanel.title(
                        MessageRenderer.of(messages)
                                .format(locale, MESSAGES.smp().objectives().title()),
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

    /** The same milestone on another page: what a page button opens. */
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
        return card < 0
                ? Optional.empty()
                : entries.stream().filter(entry -> entry.card() == card).findFirst();
    }

    public boolean isPrevious(final int slot) {
        return slot == ObjectivePanel.PREV_SLOT && page > 0;
    }

    public boolean isNext(final int slot) {
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
        return MilestoneNames.of(messages, locale, milestone.key());
    }

    private String name(final Objective definition) {
        return definition.key();
    }

    /** The sentence on the bottom row, as the plain bundle value because {@code MenuFont} draws it. */
    private String shareLine() {
        if (share.empty()) {
            return messages.format(locale, MESSAGES.smp().objectives().shareNone());
        }
        return messages.format(locale, MESSAGES.smp().objectives().share(share.spins()));
    }

    private void fill() {
        final ItemStack heading = BlankItem.of(
                MessageRenderer.of(messages)
                        .format(locale, MESSAGES.smp().objectives().heading(new MilestoneContext(milestoneName()))),
                List.of(MessageRenderer.of(messages)
                        .format(locale, MESSAGES.smp().objectives().headingHint(finished(), rows.size()))));
        for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
            inventory.setItem(SlotGeometry.slot(column, ObjectivePanel.HEADING_ROW), heading);
        }

        for (final Entry entry : entries) {
            final ItemStack item = cardItem(entry);
            ObjectivePanel.slotsOf(entry.card()).forEach(slot -> inventory.setItem(slot, item));
        }

        final ItemStack shareItem = BlankItem.of(
                MessageRenderer.of(messages)
                        .format(locale, MESSAGES.smp().objectives().shareTooltip()),
                shareLore());
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
        return BlankItem.of(MessageRenderer.of(messages).format(locale, label), List.of());
    }

    private ItemStack cardItem(final Entry entry) {
        final MessageRenderer renderer = MessageRenderer.of(messages);
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
                            .items(String.join(", ", definition.items() == null ? List.of() : definition.items()))));
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
        final MessageRenderer renderer = MessageRenderer.of(messages);
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
