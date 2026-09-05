package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.smp.board.ProgressBar;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the spawn NPC opens: the current milestone's objectives, each with what is needed and how
 * much is already there.
 *
 * <p>A {@code HAND_IN} objective is clickable and opens the deposit screen; the other two types are
 * shown and are not, because there is nothing to click - a statistic counts itself and an
 * advancement is earned somewhere else entirely.
 */
public final class ObjectiveGui implements Surface {

    private static final int FIRST_SLOT = 10;

    private final Inventory inventory;
    private final List<Entry> entries = new ArrayList<>();

    /** One line of the list: the definition, its stored progress, and where it is drawn. */
    public record Entry(Objective objective, ObjectiveRow row, int slot) {

        public boolean isHandIn() {
            return objective.type() == ObjectiveType.HAND_IN && !row.completed();
        }
    }

    public ObjectiveGui(final Messages messages, final Locale locale, final Milestone milestone,
                        final List<ObjectiveRow> rows) {
        final int size = Math.min(54, Math.max(27, ((rows.size() + 8) / 9 + 2) * 9));
        this.inventory = Bukkit.createInventory(this, size, MenuTitle.of(size / 9,
                MessageRenderer.of(messages).get(locale, "smp.objectives.title")));

        int slot = FIRST_SLOT;
        for (final ObjectiveRow row : rows) {
            final Objective definition = milestone.objective(row.key()).orElse(null);
            if (definition == null || slot >= inventory.getSize()) {
                continue;
            }
            entries.add(new Entry(definition, row, slot));
            inventory.setItem(slot, icon(messages, locale, milestone, definition, row));
            slot++;
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Optional<Entry> at(final int slot) {
        return entries.stream().filter(entry -> entry.slot() == slot).findFirst();
    }

    private static ItemStack icon(final Messages messages, final Locale locale,
                                  final Milestone milestone, final Objective definition,
                                  final ObjectiveRow row) {
        final Material material = switch (definition.type()) {
            case HAND_IN -> row.completed() ? Material.CHEST : Material.HOPPER;
            case STATISTIC -> Material.IRON_PICKAXE;
            case ADVANCEMENT -> Material.EXPERIENCE_BOTTLE;
        };

        final String nameKey = "smp.objective." + milestone.key() + "." + definition.key();
        final String label = messages.hasTranslation(locale, nameKey)
                ? messages.get(locale, nameKey) : definition.key();

        // Colours live in the bundle and the objective's name goes in as a parameter since
        // 2026-09-06 (finding 48); what stays in code is the italic flag, which is item metadata
        // and not styling of the text.
        final MessageRenderer renderer = MessageRenderer.of(messages);
        final ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(line(renderer.format(locale,
                    row.completed() ? "smp.objectives.item-done" : "smp.objectives.item",
                    "objective", label)));

            final List<Component> lore = new ArrayList<>();
            lore.add(line(renderer.format(locale, "smp.objectives.progress",
                    "bar", ProgressBar.of(row.ratio(), 20), "amount", row.amount(), "target", row.target())));
            if (row.completed()) {
                lore.add(line(renderer.get(locale, "smp.objectives.done")));
            } else if (definition.type() == ObjectiveType.HAND_IN) {
                lore.add(line(renderer.get(locale, "smp.objectives.click-to-hand-in")));
                lore.add(line(renderer.format(locale, "smp.objectives.items", "items",
                        String.join(", ", definition.items() == null ? List.of() : definition.items()))));
            } else {
                lore.add(line(renderer.get(locale, "smp.objectives.counts-itself")));
            }
            meta.lore(lore);
        });
        return stack;
    }

    private static Component line(final Component text) {
        return text.decoration(TextDecoration.ITALIC, false);
    }
}
