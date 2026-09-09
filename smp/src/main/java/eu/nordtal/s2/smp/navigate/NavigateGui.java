package eu.nordtal.s2.smp.navigate;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.papercommon.menu.BlankItem;
import eu.nordtal.s2.smp.db.PoiRow;
import eu.nordtal.s2.smp.feedback.Surface;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The list {@code /navigate} opens: the current world's spawn, the player's last death, and every
 * public POI.
 *
 * <p>POIs are public and unlimited - anyone may create one and everyone sees all of them - so this
 * list is the same for every player except for the last-death entry, which is their own. There is
 * deliberately no entry for another player: with PvP on everywhere, an arrow pointing at a person
 * is a hunting tool.
 *
 * <h2>What it looks like, and what that costs</h2>
 * The window is the {@code N1} design (owner, 2026-09-08): a painted panel with five destinations,
 * each a full-width pill carrying its kind icon, its name and how far away it is, and a control row
 * with a stop button and two page buttons. {@link NavigatePanel} draws it; every slot under the
 * paint holds a {@link BlankItem}, because a painted card with no item in it is a card nobody can
 * hover or click.
 *
 * <p>The price of painting the window is that <b>a page turn is a new inventory</b>. A chest's title
 * is fixed when it is opened, so there is no way to redraw the surface in place - the menu builds
 * its sibling page and the listener opens that. No database work happens on the way: the whole list
 * was read once when the command ran, and {@link #onPage} hands it on.
 *
 * <p>The first control always turns navigation <em>off</em>. It is off by default, a player switched
 * it on, and the way back has to be as easy as the way in - and as visible, because an arrow nobody
 * asked for any more is the kind of thing that quietly annoys somebody for a week.
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

    public NavigateGui(final Messages messages, final PlayerLocales locales,
                       final Navigation navigation, final Player viewer,
                       final Optional<NavigationTarget> lastDeath, final List<PoiRow> pois) {
        this(messages, locales.of(viewer.getUniqueId()), navigation,
                build(viewer, lastDeath, pois), 0, viewer);
    }

    private NavigateGui(final Messages messages, final Locale locale, final Navigation navigation,
                        final List<NavigationTarget> targets, final int page, final Player viewer) {
        this.messages = messages;
        this.locale = locale;
        this.navigation = navigation;
        this.targets = targets;
        this.page = NavigatePage.clamp(page, targets.size());
        this.world = viewer.getWorld().getName();
        this.x = viewer.getLocation().getX();
        this.y = viewer.getLocation().getY();
        this.z = viewer.getLocation().getZ();

        final Optional<NavigationTarget> active = navigation.of(viewer.getUniqueId());
        final List<NavigatePanel.Entry> entries = NavigatePage.entries(targets, this.page,
                world, x, y, z, active, messages, locale);

        this.inventory = Bukkit.createInventory(this, NavigatePanel.ROWS * SlotGeometry.COLUMNS,
                NavigatePanel.title(
                        MessageRenderer.of(messages).get(locale, "smp.navigate.title"),
                        entries,
                        messages.get(locale, "smp.navigate.stop-button"),
                        NavigatePage.pageLabel(this.page, targets.size(), messages, locale),
                        this.page > 0,
                        this.page < NavigatePage.pages(targets.size()) - 1));
        fill();
    }

    private static List<NavigationTarget> build(final Player viewer,
                                                final Optional<NavigationTarget> lastDeath,
                                                final List<PoiRow> pois) {
        final List<NavigationTarget> out = new ArrayList<>();
        out.add(NavigationTarget.worldSpawn(viewer.getWorld().getName(),
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
     * The same list, drawn on another page - what a page button opens.
     *
     * <p>The player is a parameter rather than a field for the reason a menu should never hold one:
     * this object outlives the window, and a {@code Player} kept in it is a logged-out player kept
     * alive by a listener. A page turn happens inside a click, where the viewer is at hand.</p>
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
                MessageRenderer.of(messages).get(locale, "smp.navigate.stop"),
                List.of(MessageRenderer.of(messages).get(locale, "smp.navigate.stop-hint")));
        NavigatePanel.STOP_SLOTS.forEach(slot -> inventory.setItem(slot, stop));

        final int pages = NavigatePage.pages(targets.size());
        if (page > 0) {
            inventory.setItem(NavigatePanel.PREV_SLOT, pageItem("smp.navigate.previous-page"));
        }
        if (page < pages - 1) {
            inventory.setItem(NavigatePanel.NEXT_SLOT, pageItem("smp.navigate.next-page"));
        }
    }

    private ItemStack pageItem(final String key) {
        return BlankItem.of(MessageRenderer.of(messages).get(locale, key), List.of());
    }

    private ItemStack entryItem(final NavigationTarget target) {
        // A POI name is player-typed: it goes in as a parameter so MessageRenderer escapes it,
        // which is the one place a `<click:...>` in a POI name could otherwise run in somebody
        // else's menu (finding 48). The colour is the bundle's.
        return BlankItem.of(
                MessageRenderer.of(messages).format(locale, "smp.navigate.target",
                        "target", NavigatePage.label(target, messages, locale)),
                List.of(MessageRenderer.of(messages).format(locale, "smp.navigate.at",
                                "world", target.world(), "x", target.x(),
                                "y", target.y(), "z", target.z()),
                        MessageRenderer.of(messages).get(locale, "smp.navigate.click")));
    }

    /** What a click did: what to play, and whether to close the window or open another. */
    public record Click(Feedback sound, boolean close, NavigateGui open) {

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
            player.sendMessage(MessageRenderer.of(messages).get(locale, "smp.navigate.stopped"));
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
        player.sendMessage(MessageRenderer.of(messages).format(locale, "smp.navigate.started",
                "target", NavigatePage.label(target, messages, locale)));
        return Click.closing();
    }
}
