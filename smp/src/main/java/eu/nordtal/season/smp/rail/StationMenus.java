package eu.nordtal.season.smp.rail;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.feedback.SmpSounds;
import io.papermc.paper.dialog.Dialog;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.jspecify.annotations.Nullable;

/**
 * Opens the station dialog on a click on a station rail, and the ride list when a player sits down in a minecart.
 * It also tells a placer when a merge of two networks renamed their station.
 */
public final class StationMenus implements Listener {

    record Named(@Example("Spawn") String name) {}

    static final DocumentSpec<Named> NAMED = DocumentSpec.game(
                    "smp", "station-named", "Rails", DocumentSpec.Shape.ACTION_BAR, Named.class)
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
    private final IntSupplier near;
    private final Dialogs dialogs;
    // Read by a rider's click, after the constructor has set every field.
    private final RideDialog.Answers rideAnswers = new RideDialog.Answers() {
        @Override
        public void ride(final Player player, final RideList.Stop stop, final int page) {
            if (player.getVehicle() instanceof RideableMinecart cart && rides.drive(player, cart, stop.station())) {
                player.closeDialog();
                return;
            }
            // A station no way leads to keeps the list open, so the rider can pick another.
            sounds.play(player, Feedback.REFUSED);
            redrawRide(player, page);
        }

        @Override
        public void star(final Player player, final RideList.Stop stop, final int page) {
            networks.toggleFavourite(player.getUniqueId(), stop.station());
            redrawRide(player, page);
        }

        @Override
        public void page(final Player player, final int page) {
            redrawRide(player, page);
        }
    };

    public StationMenus(
            final RailNetworks networks,
            final Rides rides,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Identities identities,
            final SmpSounds sounds,
            final PaperScheduler scheduler,
            final Dialogs dialogs,
            final IntSupplier near) {
        this.networks = networks;
        this.rides = rides;
        this.renderer = renderer;
        this.documents = documents;
        this.identities = identities;
        this.sounds = sounds;
        this.scheduler = scheduler;
        this.dialogs = dialogs;
        this.near = near;
        networks.whenRenamedByMerge(this::tellRenamed);
        // One hologram serves every viewer, so the spawn station's shows its English name.
        networks.whenSpawnNamed(() -> spawnName(Locale.ENGLISH));
        rides.whenStranded(this::stranded);
    }

    private Locale locale(final Player player) {
        return identities.languageOf(player.getUniqueId());
    }

    private boolean mayName(final Player player, final StationRow station) {
        return station.mayChange(player.getUniqueId(), identities.of(player).admin());
    }

    /**
     * A right click on a station rail opens its dialog, unless a minecart in the main hand seats the player.
     * A left click still breaks it.
     * A rider's right click on a block that has no click of its own opens the ride list again.
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
                || NetworkCarts.seatsOnStation(player)
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

    /** A right click with an empty hand on the rider's own cart opens the ride list again. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND
                && event.getRightClicked().equals(event.getPlayer().getVehicle())
                && reopenedRide(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Opens the ride list again for a rider with an empty hand, when their cart stands on a network.
     * A button, a lever, a door or a chest keeps its own click, which the caller checks.
     */
    private boolean reopenedRide(final Player player) {
        if (!(player.getVehicle() instanceof RideableMinecart)
                || player.getInventory().getItemInMainHand().getType() != Material.AIR) {
            return false;
        }
        return openRide(player);
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
        final GameDocument document = stationDocument(locale(player), StationDialog.DOCUMENT);
        if (!mayName(player, station)) {
            player.showDialog(
                    StationDialog.visitor(document, station.spawn() ? spawnName(locale(player)) : station.name()));
            return;
        }
        final @Nullable String name = station.name();
        showPlacer(player, station, name == null ? "" : name, !networks.hidden(station.id()), null);
    }

    private void showPlacer(
            final Player player,
            final StationRow station,
            final String typed,
            final boolean shown,
            final @Nullable String refusal) {
        final GameDocument document = stationDocument(locale(player), StationDialog.DOCUMENT);
        player.showDialog(StationDialog.placer(
                document,
                typed,
                shown,
                refusal == null ? null : document.label(refusal, new StationDialog.Most(StationNames.MAX_LENGTH)),
                dialogs.send(),
                (saver, name, show) -> saved(saver, station, name, show)));
    }

    private GameDocument stationDocument(final Locale locale, final DocumentSpec<DocumentSpec.None> spec) {
        return new GameDocument(renderer, documents.applied(spec), Viewer.of(locale), new DocumentSpec.None());
    }

    /** Returns the spawn station's name in {@code locale}, from the translations. */
    private String spawnName(final Locale locale) {
        return stationDocument(locale, StationDialog.DOCUMENT).label("spawn");
    }

    /** Opens the ride list for the cart {@code player} sits in, and returns whether it stands on a network. */
    boolean openRide(final Player player) {
        final Optional<Dialog> dialog = ride(player, 0);
        dialog.ifPresent(player::showDialog);
        return dialog.isPresent();
    }

    /**
     * Returns whether the ride list holds a station to pick.
     * It answers for a rider whose cart stands on a network with no ride driving it, and is empty for anyone else.
     */
    public Optional<Boolean> waiting(final Player player) {
        if (!(player.getVehicle() instanceof RideableMinecart cart) || rides.driving(cart)) {
            return Optional.empty();
        }
        return networkUnder(cart)
                .map(ride -> !RideList.of(ride.graph(), ride.at(), ride.stations(), Set.of(), near.getAsInt(), "")
                        .isEmpty());
    }

    private Optional<RailNetworks.Ride> networkUnder(final RideableMinecart cart) {
        final Block at = cart.getLocation().getBlock();
        final Block rail = Tag.RAILS.isTagged(at.getType()) ? at : at.getRelative(BlockFace.DOWN);
        return networks.ride(rail);
    }

    /** Returns the ride list at {@code page} for the cart {@code player} sits in, when it stands on a network. */
    private Optional<Dialog> ride(final Player player, final int page) {
        if (!(player.getVehicle() instanceof RideableMinecart cart)) {
            return Optional.empty();
        }
        return networkUnder(cart)
                .map(ride -> RideDialog.of(
                        stationDocument(locale(player), RideDialog.DOCUMENT),
                        RideList.of(
                                ride.graph(),
                                ride.at(),
                                ride.stations(),
                                networks.favourites(player.getUniqueId()),
                                near.getAsInt(),
                                spawnName(locale(player))),
                        page,
                        dialogs.send(),
                        rideAnswers));
    }

    /** Redraws the ride list at {@code page}, or closes it when the cart left the network. */
    private void redrawRide(final Player player, final int page) {
        ride(player, page).ifPresentOrElse(player::showDialog, player::closeDialog);
    }

    /** Tells a rider whose cart stopped before the station that it goes no further, and shows the stations left. */
    private void stranded(final Player player) {
        player.sendMessage(renderer.format(locale(player), Sent.of(STOPPED)));
        openRide(player);
    }

    private void saved(final Player player, final StationRow asked, final String typed, final boolean shown) {
        if (!player.isOnline()) {
            return;
        }
        // The spawn station shows its name in the viewer's language and on its hologram in English.
        final Optional<StationNames.Verdict> verdict = mayName(player, asked)
                ? networks.rename(asked, typed, List.of(spawnName(locale(player)), spawnName(Locale.ENGLISH)))
                : Optional.empty();
        if (verdict.isEmpty()) {
            sounds.play(player, Feedback.REFUSED);
            player.closeDialog();
            return;
        }
        final @Nullable String refusal = refusal(verdict.get());
        if (refusal != null) {
            sounds.play(player, Feedback.REFUSED);
            showPlacer(player, asked, typed, shown, refusal);
            return;
        }
        if (togglesHidden(networks.hidden(asked.id()), shown)) {
            networks.toggleHidden(asked.id());
        }
        sounds.play(player, Feedback.SMALL_SUCCESS);
        player.closeDialog();
        player.sendActionBar(Lines.joined(renderer.format(locale(player), NAMED.with(new Named(typed.strip())))));
    }

    /** The label that says why a save was refused, or {@code null} for an accepted one. */
    static @Nullable String refusal(final StationNames.Verdict verdict) {
        return switch (verdict) {
            case ACCEPTED -> null;
            case EMPTY -> "name-empty";
            case TOO_LONG -> "name-too-long";
            case TAKEN -> "name-taken";
        };
    }

    /** Whether a save changes the map: a hidden station the save shows, or a shown one it hides. */
    static boolean togglesHidden(final boolean hidden, final boolean shown) {
        return hidden == shown;
    }

    /** Tells the placer of {@code station} its new name when they are online; otherwise their next join does. */
    private void tellRenamed(final StationRow station) {
        final @Nullable UUID placedBy = station.placedBy();
        final @Nullable Player placer = placedBy == null ? null : Bukkit.getPlayer(placedBy);
        final String name = station.name();
        if (placer == null || !placer.isOnline() || name == null) {
            return;
        }
        placer.sendMessage(renderer.format(
                locale(placer), RENAMED.with(new Renamed(name, station.x(), station.y(), station.z()))));
        networks.seen(station.id());
    }
}
