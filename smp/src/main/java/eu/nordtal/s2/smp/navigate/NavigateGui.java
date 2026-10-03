package eu.nordtal.s2.smp.navigate;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.smp.db.PoiRow;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.menu.BlankItem;
import eu.nordtal.s2.smp.menu.SlotGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The list {@code /navigate} opens: the current world's spawn, the player's last death, and every public POI.
 *
 * There is no entry for another player, and a page turn opens a new inventory because a title cannot be redrawn.
 */
public final class NavigateGui implements Surface {

    private final Messages messages;
    private final Navigation navigation;
    private final List<NavigationTarget> targets;
    private final int page;
    private final Locale locale;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final Inventory inventory;

    public NavigateGui(
            final Messages messages,
            final Identities identities,
            final Navigation navigation,
            final Player viewer,
            final Optional<NavigationTarget> lastDeath,
            final List<PoiRow> pois) {
        this(
                messages,
                identities.languageOf(viewer.getUniqueId()),
                navigation,
                build(viewer, lastDeath, pois),
                0,
                viewer);
    }

    private NavigateGui(
            final Messages messages,
            final Locale locale,
            final Navigation navigation,
            final List<NavigationTarget> targets,
            final int page,
            final Player viewer) {
        this.messages = messages;
        this.locale = locale;
        this.navigation = navigation;
        this.targets = targets;
        this.page = NavigatePage.clamp(page, targets.size());
        final Location at = Objects.requireNonNull(viewer.getLocation());
        this.world = viewer.getWorld().getName();
        this.x = at.getX();
        this.y = at.getY();
        this.z = at.getZ();

        final Optional<NavigationTarget> active = navigation.of(viewer.getUniqueId());
        final List<NavigatePanel.Entry> entries =
                NavigatePage.entries(targets, this.page, world, x, y, z, active, messages, locale);

        this.inventory = Bukkit.createInventory(
                this,
                NavigatePanel.ROWS * SlotGeometry.COLUMNS,
                NavigatePanel.title(
                        MessageRenderer.of(messages)
                                .format(locale, MESSAGES.smp().navigate().title()),
                        entries,
                        messages.format(locale, MESSAGES.smp().navigate().stopButton()),
                        NavigatePage.pageLabel(this.page, targets.size(), messages, locale),
                        this.page > 0,
                        this.page < NavigatePage.pages(targets.size()) - 1));
        fill();
    }

    private static List<NavigationTarget> build(
            final Player viewer, final Optional<NavigationTarget> lastDeath, final List<PoiRow> pois) {
        final List<NavigationTarget> out = new ArrayList<>();
        out.add(NavigationTarget.worldSpawn(
                viewer.getWorld().getName(),
                viewer.getWorld().getSpawnLocation().getBlockX(),
                viewer.getWorld().getSpawnLocation().getBlockY(),
                viewer.getWorld().getSpawnLocation().getBlockZ()));
        lastDeath.ifPresent(out::add);
        for (final PoiRow poi : pois) {
            out.add(NavigationTarget.poi(poi.id(), poi.name(), poi.world(), poi.x(), poi.y(), poi.z()));
        }
        return List.copyOf(out);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * The same list, drawn on another page: what a page button opens.
     *
     * The viewer is a parameter, because a menu that holds a {@code Player} keeps a logged-out one alive.
     */
    public NavigateGui onPage(final int wanted, final Player viewer) {
        return new NavigateGui(messages, locale, navigation, targets, wanted, viewer);
    }

    private void fill() {
        final List<NavigationTarget> shown = NavigatePage.slice(targets, page);
        for (int row = 0; row < shown.size(); row++) {
            final NavigationTarget target = shown.get(row);
            final ItemStack item = entryItem(target);
            for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
                inventory.setItem(SlotGeometry.slot(column, row), item);
            }
        }

        final ItemStack stop = BlankItem.of(
                MessageRenderer.of(messages)
                        .format(locale, MESSAGES.smp().navigate().stop()),
                List.of(MessageRenderer.of(messages)
                        .format(locale, MESSAGES.smp().navigate().stopHint())));
        NavigatePanel.STOP_SLOTS.forEach(slot -> inventory.setItem(slot, stop));

        final int pages = NavigatePage.pages(targets.size());
        inventory.setItem(
                NavigatePanel.PREV_SLOT,
                page > 0 ? pageItem(MESSAGES.smp().navigate().previousPage()) : null);
        inventory.setItem(
                NavigatePanel.NEXT_SLOT,
                page < pages - 1 ? pageItem(MESSAGES.smp().navigate().nextPage()) : null);
    }

    private ItemStack pageItem(final MessageRef label) {
        return BlankItem.of(MessageRenderer.of(messages).format(locale, label), List.of());
    }

    private ItemStack entryItem(final NavigationTarget target) {
        // A POI name is player-typed: it goes in as a parameter so MessageRenderer escapes a `<click:...>`.
        return BlankItem.of(
                MessageRenderer.of(messages)
                        .format(locale, MESSAGES.smp().navigate().target(NavigatePage.label(target, messages, locale))),
                List.of(
                        MessageRenderer.of(messages)
                                .format(
                                        locale,
                                        MESSAGES.smp()
                                                .navigate()
                                                .at(target.world(), target.x(), target.y(), target.z())),
                        MessageRenderer.of(messages)
                                .format(locale, MESSAGES.smp().navigate().click())));
    }

    public record Click(
            @Nullable Feedback sound,
            boolean close,
            @Nullable NavigateGui open) {

        static Click nothing() {
            return new Click(null, false, null);
        }

        static Click refused() {
            return new Click(Feedback.REFUSED, false, null);
        }

        static Click closing() {
            return new Click(Feedback.SELECT, true, null);
        }

        static Click opening(final NavigateGui gui) {
            return new Click(Feedback.SELECT, false, gui);
        }
    }

    /** Handles a click on a raw slot of this window. */
    public Click click(final Player player, final int slot) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return Click.nothing();
        }
        if (NavigatePanel.STOP_SLOTS.contains(slot)) {
            navigation.clear(player.getUniqueId());
            player.sendMessage(MessageRenderer.of(messages)
                    .format(locale, MESSAGES.smp().navigate().stopped()));
            return Click.closing();
        }
        if (slot == NavigatePanel.PREV_SLOT || slot == NavigatePanel.NEXT_SLOT) {
            final int wanted = page + (slot == NavigatePanel.PREV_SLOT ? -1 : 1);
            // A greyed button is still a button, so the refusal is a sound and not silence.
            return wanted < 0 || wanted >= NavigatePage.pages(targets.size())
                    ? Click.refused()
                    : Click.opening(onPage(wanted, player));
        }

        final List<NavigationTarget> shown = NavigatePage.slice(targets, page);
        final int row = SlotGeometry.row(slot);
        if (row >= shown.size()) {
            return Click.nothing();
        }
        final NavigationTarget target = shown.get(row);
        navigation.set(player.getUniqueId(), target);
        player.sendMessage(MessageRenderer.of(messages)
                .format(locale, MESSAGES.smp().navigate().started(NavigatePage.label(target, messages, locale))));
        return Click.closing();
    }
}
