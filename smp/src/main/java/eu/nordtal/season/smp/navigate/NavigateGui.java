package eu.nordtal.season.smp.navigate;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
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

public final class NavigateGui extends Menu {

    /** @param page the shown page, counted from 1 */
    record Window(@Example("1") long page, @Example("3") long pages) {}

    record Target(
            @Example("Home") String target,
            @Example("world") String world,
            @Example("-120") long x,
            @Example("64") long y,
            @Example("355") long z) {}

    record Distance(@Example("350") long blocks) {}

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
            "smp",
            "navigate",
            "Menus",
            Window.class,
            Slot.item("target", Target.class),
            Slot.label("distance", Distance.class),
            Slot.label("other-world"),
            Slot.item("stop"),
            Slot.label("stop-button"),
            Slot.item("previous-page"),
            Slot.label("page"),
            Slot.item("next-page"));

    record Started(@Example("Home") String target) {}

    static final DocumentSpec<Started> STARTED =
            DocumentSpec.game("smp", "navigate-started", "Navigate", DocumentSpec.Shape.CHAT, Started.class);

    public static final DocumentSpec<DocumentSpec.None> STOPPED =
            DocumentSpec.game("smp", "navigate-stopped", "Navigate", DocumentSpec.Shape.CHAT);

    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final GameDocument document;
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
            final AppliedDocuments documents,
            final Identities identities,
            final Navigation navigation,
            final Player viewer,
            final Optional<NavigationTarget> lastDeath,
            final List<PoiRow> pois) {
        this(
                renderer,
                documents,
                identities.languageOf(viewer.getUniqueId()),
                navigation,
                build(viewer, lastDeath, pois),
                0,
                viewer);
    }

    private NavigateGui(
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Locale locale,
            final Navigation navigation,
            final List<NavigationTarget> targets,
            final int page,
            final Player viewer) {
        this.renderer = renderer;
        this.documents = documents;
        this.locale = locale;
        this.navigation = navigation;
        this.targets = targets;
        this.page = NavigatePage.clamp(page, targets.size());
        this.document = new GameDocument(
                renderer,
                documents.applied(DOCUMENT),
                Viewer.of(locale),
                new Window(this.page + 1, NavigatePage.pages(targets.size())));
        final Location at = Objects.requireNonNull(viewer.getLocation());
        this.world = viewer.getWorld().getName();
        this.x = at.getX();
        this.y = at.getY();
        this.z = at.getZ();

        final Optional<NavigationTarget> active = navigation.of(viewer.getUniqueId());
        final List<NavigatePanel.Entry> entries =
                NavigatePage.entries(targets, this.page, world, x, y, z, active, document, renderer, locale);

        this.inventory = frame(
                NavigatePanel.ROWS,
                NavigatePanel.title(
                        document.title(),
                        entries,
                        document.label("stop-button"),
                        document.label("page"),
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

    /** Returns this list on page {@code wanted}. The menu keeps no {@code Player}, so the caller passes the viewer. */
    public NavigateGui onPage(final int wanted, final Player viewer) {
        return new NavigateGui(renderer, documents, locale, navigation, targets, wanted, viewer);
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

        final ItemStack stop = BlankItem.of(document.item("stop"));
        NavigatePanel.STOP_SLOTS.forEach(slot -> inventory.setItem(slot, stop));

        final int pages = NavigatePage.pages(targets.size());
        inventory.setItem(NavigatePanel.PREV_SLOT, page > 0 ? BlankItem.of(document.item("previous-page")) : null);
        inventory.setItem(NavigatePanel.NEXT_SLOT, page < pages - 1 ? BlankItem.of(document.item("next-page")) : null);
    }

    private ItemStack entryItem(final NavigationTarget target) {
        // A POI name is a value, so MessageRenderer escapes any tag in it.
        return BlankItem.of(document.item(
                "target",
                new Target(
                        NavigatePage.label(target, renderer, locale),
                        target.world(),
                        target.x(),
                        target.y(),
                        target.z())));
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        if (NavigatePanel.STOP_SLOTS.contains(slot)) {
            navigation.clear(player.getUniqueId());
            player.sendMessage(renderer.format(locale, Sent.of(STOPPED)));
            return MenuClick.closing();
        }
        if (slot == NavigatePanel.PREV_SLOT || slot == NavigatePanel.NEXT_SLOT) {
            final int wanted = page + (slot == NavigatePanel.PREV_SLOT ? -1 : 1);
            // A click on a page button without a page plays the refusal sound.
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
        player.sendMessage(
                renderer.format(locale, STARTED.with(new Started(NavigatePage.label(target, renderer, locale)))));
        return MenuClick.closing();
    }
}
