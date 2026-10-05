package eu.nordtal.season.smp.navigate;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import eu.nordtal.season.papercommon.menu.SlotGeometry;
import eu.nordtal.season.papercommon.player.Identities;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The list {@code /navigate} opens: the current world's spawn, the player's last death, and every public POI.
 *
 * There is no entry for another player, and a page turn opens a new inventory because a title cannot be redrawn.
 */
public final class NavigateGui extends Menu {

    private final MessageRenderer renderer;
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
            final MessageRenderer renderer,
            final Identities identities,
            final Navigation navigation,
            final Player viewer,
            final Optional<NavigationTarget> lastDeath,
            final List<PoiRow> pois) {
        this(
                renderer,
                identities.languageOf(viewer.getUniqueId()),
                navigation,
                build(viewer, lastDeath, pois),
                0,
                viewer);
    }

    private NavigateGui(
            final MessageRenderer renderer,
            final Locale locale,
            final Navigation navigation,
            final List<NavigationTarget> targets,
            final int page,
            final Player viewer) {
        this.renderer = renderer;
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
                NavigatePage.entries(targets, this.page, world, x, y, z, active, renderer.raw(), locale);

        this.inventory = frame(
                NavigatePanel.ROWS,
                NavigatePanel.title(
                        renderer.format(locale, MESSAGES.smp().navigate().title()),
                        entries,
                        renderer.raw().format(locale, MESSAGES.smp().navigate().stopButton()),
                        NavigatePage.pageLabel(this.page, targets.size(), renderer.raw(), locale),
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

    /**
     * The same list, drawn on another page: what a page button opens.
     *
     * The viewer is a parameter, because a menu that holds a {@code Player} keeps a logged-out one alive.
     */
    public NavigateGui onPage(final int wanted, final Player viewer) {
        return new NavigateGui(renderer, locale, navigation, targets, wanted, viewer);
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
                renderer.format(locale, MESSAGES.smp().navigate().stop()),
                List.of(renderer.format(locale, MESSAGES.smp().navigate().stopHint())));
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
        return BlankItem.of(renderer.format(locale, label), List.of());
    }

    private ItemStack entryItem(final NavigationTarget target) {
        // A POI name is player-typed: it goes in as a parameter so MessageRenderer escapes a `<click:...>`.
        return BlankItem.of(
                renderer.format(
                        locale, MESSAGES.smp().navigate().target(NavigatePage.label(target, renderer.raw(), locale))),
                List.of(
                        renderer.format(
                                locale,
                                MESSAGES.smp().navigate().at(target.world(), target.x(), target.y(), target.z())),
                        renderer.format(locale, MESSAGES.smp().navigate().click())));
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        if (NavigatePanel.STOP_SLOTS.contains(slot)) {
            navigation.clear(player.getUniqueId());
            player.sendMessage(renderer.format(locale, MESSAGES.smp().navigate().stopped()));
            return MenuClick.closing();
        }
        if (slot == NavigatePanel.PREV_SLOT || slot == NavigatePanel.NEXT_SLOT) {
            final int wanted = page + (slot == NavigatePanel.PREV_SLOT ? -1 : 1);
            // A greyed button is still a button, so the refusal is a sound and not silence.
            return wanted < 0 || wanted >= NavigatePage.pages(targets.size())
                    ? MenuClick.refused()
                    : MenuClick.opening(onPage(wanted, player));
        }

        final List<NavigationTarget> shown = NavigatePage.slice(targets, page);
        final int row = SlotGeometry.row(slot);
        if (row >= shown.size()) {
            return MenuClick.nothing();
        }
        final NavigationTarget target = shown.get(row);
        navigation.set(player.getUniqueId(), target);
        player.sendMessage(renderer.format(
                locale, MESSAGES.smp().navigate().started(NavigatePage.label(target, renderer.raw(), locale))));
        return MenuClick.closing();
    }
}
