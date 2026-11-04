package eu.nordtal.season.smp.rail;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.feedback.SmpSounds;
import io.papermc.paper.dialog.Dialog;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Opens the station menu on a click on a station rail, and the ride menu when a player sits down in a minecart.
 * It also shows the naming dialog and tells a placer when a merge of two networks renamed their station.
 */
public final class StationMenus implements Listener {

    record Named(@Example("Spawn") String name) {}

    static final DocumentSpec<Named> NAMED = DocumentSpec.game(
                    "smp", "station-named", "Rails", DocumentSpec.Shape.CHAT, Named.class)
            .titled("Station named");

    record Renamed(
            @Example("Spawn 2") String name,
            @Example("-120") long x,
            @Example("64") long y,
            @Example("355") long z) {}

    static final DocumentSpec<Renamed> RENAMED = DocumentSpec.game(
                    "smp", "station-renamed", "Rails", DocumentSpec.Shape.CHAT, Renamed.class)
            .titled("Station renamed");

    static final DocumentSpec<DocumentSpec.None> STOPPED = DocumentSpec.game(
                    "smp", "ride-stopped", "Rails", DocumentSpec.Shape.CHAT)
            .titled("Ride stopped");

    private final RailNetworks networks;
    private final Rides rides;
    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final Identities identities;
    private final SmpSounds sounds;
    private final PaperScheduler scheduler;
    private final IntSupplier perPage;
    private final IntSupplier near;

    public StationMenus(
            final RailNetworks networks,
            final Rides rides,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Identities identities,
            final SmpSounds sounds,
            final PaperScheduler scheduler,
            final IntSupplier perPage,
            final IntSupplier near) {
        this.networks = networks;
        this.rides = rides;
        this.renderer = renderer;
        this.documents = documents;
        this.identities = identities;
        this.sounds = sounds;
        this.scheduler = scheduler;
        this.perPage = perPage;
        this.near = near;
        networks.whenRenamedByMerge(this::tellRenamed);
        rides.whenStranded(this::stranded);
    }

    private Locale locale(final Player player) {
        return identities.languageOf(player.getUniqueId());
    }

    private boolean mayName(final Player player, final StationRow station) {
        return identities.of(player).admin() || station.placedBy().equals(player.getUniqueId());
    }

    /**
     * A right click on a station rail opens its menu, unless it places a minecart; a left click still breaks it.
     * A rider's right click on a block that has no click of its own opens the ride menu again.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player player = event.getPlayer();
        final Block block = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && block != null
                && !block.getType().isInteractable()
                && reopenedRide(player)) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || block == null
                || event.useInteractedBlock() == Event.Result.DENY
                || placesMinecart(event.getItem())
                || !networks.isStation(block)) {
            return;
        }
        event.setCancelled(true);
        final Optional<StationRow> station = networks.station(block);
        if (station.isEmpty()) {
            // The store has not answered yet, right after a start.
            sounds.play(player, Feedback.REFUSED);
            return;
        }
        openStation(player, station.get());
    }

    private static boolean placesMinecart(final @Nullable ItemStack item) {
        return item != null && item.getType().name().endsWith("MINECART");
    }

    /** A right click with an empty hand on the rider's own cart opens the ride menu again. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND
                && event.getRightClicked().equals(event.getPlayer().getVehicle())
                && reopenedRide(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Opens the ride menu again for a rider with an empty hand, when their cart stands on a network.
     * A button, a lever, a door or a chest keeps its own click, which the caller checks.
     */
    private boolean reopenedRide(final Player player) {
        if (!(player.getVehicle() instanceof RideableMinecart)
                || player.getInventory().getItemInMainHand().getType() != Material.AIR) {
            return false;
        }
        final Optional<RideMenu> menu = ride(player, 0);
        menu.ifPresent(open -> open.open(player));
        return menu.isPresent();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEnter(final VehicleEnterEvent event) {
        if (event.getVehicle() instanceof RideableMinecart cart && event.getEntered() instanceof Player player) {
            // The player sits in the cart only after the event.
            scheduler.onMainAfter(PaperScheduler.TICK, () -> {
                if (player.isOnline() && cart.equals(player.getVehicle())) {
                    openRide(player);
                }
            });
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        networks.unseenOf(event.getPlayer().getUniqueId()).forEach(this::tellRenamed);
    }

    private void openStation(final Player player, final StationRow station) {
        final GameDocument document = stationDocument(player, station, "");
        new StationMenu(this, station, mayName(player, station), document).open(player);
    }

    private GameDocument stationDocument(final Player player, final StationRow station, final String typed) {
        final String name = station.name();
        return new GameDocument(
                renderer,
                documents.applied(StationMenu.DOCUMENT),
                Viewer.of(locale(player)),
                new StationMenu.Window(name != null, name == null ? "" : name, typed, StationNames.MAX_LENGTH));
    }

    private void openRide(final Player player) {
        ride(player, 0).ifPresent(menu -> menu.open(player));
    }

    /** Returns the ride menu at {@code page} for the cart {@code player} sits in, when it stands on a network. */
    Optional<RideMenu> ride(final Player player, final int page) {
        if (!(player.getVehicle() instanceof RideableMinecart cart)) {
            return Optional.empty();
        }
        final Block at = cart.getLocation().getBlock();
        final Block rail = Tag.RAILS.isTagged(at.getType()) ? at : at.getRelative(BlockFace.DOWN);
        return networks.ride(rail).map(ride -> {
            final List<RideList.Stop> stops =
                    RideList.of(
                            ride.graph(),
                            ride.at(),
                            ride.stations(),
                            networks.favourites(player.getUniqueId()),
                            near.getAsInt());
            final int each = Math.clamp(perPage.getAsInt(), 1, RidePanel.MOST_PER_PAGE);
            final int pages = RidePanel.pages(stops.size(), each);
            final int shown = Math.clamp(page, 0, pages - 1);
            final GameDocument document = new GameDocument(
                    renderer,
                    documents.applied(RideMenu.DOCUMENT),
                    Viewer.of(locale(player)),
                    new RideMenu.Window(shown + 1, pages));
            return new RideMenu(this, stops, each, shown, document);
        });
    }

    /** Starts the cart {@code player} sits in towards {@code stop}, or changes where it goes. */
    void pick(final Player player, final RideList.Stop stop) {
        if (player.getVehicle() instanceof RideableMinecart cart && rides.drive(player, cart, stop.station())) {
            sounds.play(player, Feedback.SELECT);
        } else {
            sounds.play(player, Feedback.REFUSED);
        }
    }

    /** Tells a rider whose cart stopped before the station that it goes no further, and shows the stations left. */
    private void stranded(final Player player) {
        player.sendMessage(renderer.format(locale(player), Sent.of(STOPPED)));
        openRide(player);
    }

    void toggleFavourite(final Player player, final RideList.Stop stop) {
        networks.toggleFavourite(player.getUniqueId(), stop.station());
        sounds.play(player, Feedback.SELECT);
    }

    /** Shows the naming dialog a tick on, once the menu that asked for it is closed. */
    void askName(final Player player, final StationRow station, final @Nullable String refusal, final String typed) {
        scheduler.onMainAfter(PaperScheduler.TICK, () -> {
            if (player.isOnline()) {
                player.showDialog(nameDialog(player, station, refusal, typed));
            }
        });
    }

    private Dialog nameDialog(
            final Player player, final StationRow station, final @Nullable String refusal, final String typed) {
        final GameDocument document = stationDocument(player, station, typed);
        final String current = station.name();
        final StationDialog.Texts texts = new StationDialog.Texts(
                document.label("dialog-title"),
                refusal == null ? null : document.label(refusal),
                document.label("dialog-rules"),
                document.label("dialog-field"),
                document.label("dialog-save"),
                document.label("dialog-cancel"));
        return StationDialog.of(
                texts,
                refusal != null ? typed : current == null ? "" : current,
                answer -> scheduler.onMain(() -> named(player, station, answer)));
    }

    private void named(final Player player, final StationRow asked, final String typed) {
        if (!player.isOnline() || !mayName(player, asked)) {
            return;
        }
        final Optional<StationNames.Verdict> verdict = networks.rename(asked, typed);
        if (verdict.isEmpty()) {
            sounds.play(player, Feedback.REFUSED);
            return;
        }
        switch (verdict.get()) {
            case ACCEPTED -> {
                sounds.play(player, Feedback.SMALL_SUCCESS);
                player.sendMessage(renderer.format(locale(player), NAMED.with(new Named(typed.strip()))));
            }
            case EMPTY -> refuse(player, asked, "name-empty", typed);
            case TOO_LONG -> refuse(player, asked, "name-too-long", typed);
            case TAKEN -> refuse(player, asked, "name-taken", typed);
        }
    }

    private void refuse(final Player player, final StationRow asked, final String refusal, final String typed) {
        sounds.play(player, Feedback.REFUSED);
        askName(player, asked, refusal, typed.strip());
    }

    /** Tells the placer of {@code station} its new name when they are online; otherwise their next join does. */
    private void tellRenamed(final StationRow station) {
        final @Nullable Player placer = Bukkit.getPlayer(station.placedBy());
        final String name = station.name();
        if (placer == null || !placer.isOnline() || name == null) {
            return;
        }
        placer.sendMessage(renderer.format(
                locale(placer), RENAMED.with(new Renamed(name, station.x(), station.y(), station.z()))));
        networks.seen(station.id());
    }
}
