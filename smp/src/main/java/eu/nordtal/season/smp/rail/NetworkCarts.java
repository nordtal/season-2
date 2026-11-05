package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.util.function.Consumer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a minecart on a rail network only while it is ridden.
 * A right click on a network's rail seats the player in a minecart from their inventory, and no other is placed
 * there. A rider who logs in seated gets the ride menu, and a cart a crash left floating falls again.
 */
public final class NetworkCarts implements Listener {

    static final DocumentSpec<DocumentSpec.None> NO_MINECART = DocumentSpec.game(
                    "smp", "ride-no-minecart", "Rails", DocumentSpec.Shape.ACTION_BAR)
            .titled("No minecart to ride");

    /** What the networks tell about a rail or a cart. */
    interface Track {

        /** Whether {@code rail} is a rail on the track of a network. */
        boolean networkRail(Block rail);

        /** Whether {@code cart} stands on the track of a network. */
        boolean onNetwork(Entity cart);

        /** Whether {@code cart} is being driven now. */
        boolean driven(Entity cart);

        /** Opens the ride menu for {@code rider}, who sits in a cart on a network. */
        void openRide(Player rider);
    }

    /** What a player is told when a click does nothing. */
    interface Tell {

        /** Tells {@code player} that they need a minecart to ride. */
        void noMinecart(Player player);

        /** Tells {@code player} with a sound that this cannot be done here. */
        void refused(Player player);
    }

    /** What a right click on a rail does. */
    enum Click {
        /** The click does what it does in vanilla. */
        VANILLA,
        /** A minecart from the inventory is placed on the rail and the player sits down in it. */
        SEAT,
        /** The player has no minecart to sit down in. */
        NO_MINECART,
        /** The off hand's click after the main hand seated the player does nothing. */
        NOTHING
    }

    private final Track track;
    private final Tell tell;
    private final Consumer<Runnable> nextTick;

    NetworkCarts(final Track track, final Tell tell, final Consumer<Runnable> nextTick) {
        this.track = track;
        this.tell = tell;
        this.nextTick = nextTick;
    }

    /** Returns the listener for the networks of {@code networks}, telling players through the server's texts. */
    public static NetworkCarts of(
            final RailNetworks networks,
            final Rides rides,
            final StationMenus menus,
            final MessageRenderer renderer,
            final Identities identities,
            final SmpSounds sounds,
            final PaperScheduler scheduler) {
        final Track track = new Track() {
            @Override
            public boolean networkRail(final Block rail) {
                return Tag.RAILS.isTagged(rail.getType()) && networks.ride(rail).isPresent();
            }

            @Override
            public boolean onNetwork(final Entity cart) {
                return rides.onNetwork(cart);
            }

            @Override
            public boolean driven(final Entity cart) {
                return rides.driven(cart);
            }

            @Override
            public void openRide(final Player rider) {
                menus.ride(rider, 0).ifPresent(menu -> menu.open(rider));
            }
        };
        final Tell tell = new Tell() {
            @Override
            public void noMinecart(final Player player) {
                player.sendActionBar(Lines.joined(
                        renderer.format(identities.languageOf(player.getUniqueId()), Sent.of(NO_MINECART))));
                sounds.play(player, Feedback.REFUSED);
            }

            @Override
            public void refused(final Player player) {
                sounds.play(player, Feedback.REFUSED);
            }
        };
        return new NetworkCarts(track, tell, work -> scheduler.onMainAfter(PaperScheduler.TICK, work));
    }

    /**
     * Decides a right click on a rail that nothing else has taken.
     * On a network's rail a player who neither sneaks nor sits sits down with the main hand; the off hand's click
     * after it does nothing, and the rest stays vanilla.
     * @param minecart whether the player has a minecart to place, or needs none in creative mode
     */
    static Click click(
            final boolean networkRail,
            final boolean seated,
            final boolean sneaking,
            final boolean mainHand,
            final boolean minecart) {
        if (networkRail && seated && !mainHand) {
            return Click.NOTHING;
        }
        if (!networkRail || seated || sneaking || !mainHand) {
            return Click.VANILLA;
        }
        return minecart ? Click.SEAT : Click.NO_MINECART;
    }

    /** Whether a minecart may be placed on a rail by a player or a dispenser: never on a network, never seated. */
    static boolean placeable(final boolean networkRail, final boolean seated) {
        return !networkRail && !seated;
    }

    private static boolean isMinecart(final @Nullable ItemStack item) {
        return item != null && item.getType().name().endsWith("MINECART");
    }

    /**
     * Whether a right click on a station rail seats the player instead of opening the station's menu: only with a
     * minecart in the main hand, neither sneaking nor seated.
     */
    static boolean seatsOnStation(final boolean sneaking, final boolean seated, final boolean minecartInHand) {
        return !sneaking && !seated && minecartInHand;
    }

    /** Whether a right click on a station rail by {@code player} seats them, as {@link #seatsOnStation} decides. */
    static boolean seatsOnStation(final Player player) {
        return seatsOnStation(
                player.isSneaking(),
                player.isInsideVehicle(),
                player.getInventory().getItemInMainHand().getType() == Material.MINECART);
    }

    private static boolean hasMinecart(final Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                || player.getInventory().contains(Material.MINECART);
    }

    /**
     * A right click on a network's rail seats the player in a minecart from their inventory.
     * A station rail's own click opens its menu instead, unless the player holds a minecart in the main hand.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(final PlayerInteractEvent event) {
        final @Nullable Block rail = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || rail == null
                || event.useItemInHand() == Event.Result.DENY
                || !Tag.RAILS.isTagged(rail.getType())) {
            return;
        }
        final Player player = event.getPlayer();
        final Click click = click(
                track.networkRail(rail),
                player.isInsideVehicle(),
                player.isSneaking(),
                event.getHand() == EquipmentSlot.HAND,
                hasMinecart(player));
        switch (click) {
            case VANILLA -> {}
            case NOTHING -> event.setCancelled(true);
            case NO_MINECART -> {
                event.setCancelled(true);
                tell.noMinecart(player);
            }
            case SEAT -> {
                event.setCancelled(true);
                seat(player, rail);
            }
        }
    }

    /** Places a minecart from the inventory on {@code rail} and sits {@code player} down in it. */
    private void seat(final Player player, final Block rail) {
        if (player.getGameMode() != GameMode.CREATIVE && !take(player.getInventory())) {
            tell.noMinecart(player);
            return;
        }
        final boolean slope = rail.getBlockData() instanceof Rail shape
                && shape.getShape().name().startsWith("ASCENDING");
        final Location at = rail.getLocation().add(0.5, slope ? 0.5625 : 0.0625, 0.5);
        at.setYaw(player.getYaw());
        final RideableMinecart cart = rail.getWorld().spawn(at, RideableMinecart.class);
        // The ride menu opens once the player sits in the cart, as for any cart they sit down in.
        if (!cart.addPassenger(player)) {
            Rides.pocket(cart, player);
            tell.refused(player);
        }
    }

    /** Takes one minecart from {@code inventory}, the one in the main hand first; false when there is none. */
    private static boolean take(final PlayerInventory inventory) {
        final int slot = inventory.getItemInMainHand().getType() == Material.MINECART
                ? inventory.getHeldItemSlot()
                : inventory.first(Material.MINECART);
        if (slot < 0) {
            return false;
        }
        final @Nullable ItemStack stack = inventory.getItem(slot);
        if (stack == null) {
            return false;
        }
        stack.setAmount(stack.getAmount() - 1);
        inventory.setItem(slot, stack.getAmount() > 0 ? stack : null);
        return true;
    }

    /** No minecart of any kind is placed on a network's rail, and none by a player who sits in a cart. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(final EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Minecart)) {
            return;
        }
        final @Nullable Player player = event.getPlayer();
        if (!placeable(track.networkRail(event.getBlock()), player != null && player.isInsideVehicle())) {
            event.setCancelled(true);
            if (player != null) {
                tell.refused(player);
            }
        }
    }

    /** A dispenser places no minecart on a network's rail, in front of it or one below. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDispense(final BlockDispenseEvent event) {
        if (!isMinecart(event.getItem()) || !(event.getBlock().getBlockData() instanceof Directional facing)) {
            return;
        }
        final Block front = event.getBlock().getRelative(facing.getFacing());
        if (!placeable(track.networkRail(front) || track.networkRail(front.getRelative(BlockFace.DOWN)), false)) {
            event.setCancelled(true);
        }
    }

    /** A player who logs in seated in a cart on a network gets the ride menu, and a floating cart falls again. */
    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // The player sits in their cart again only once they joined.
        nextTick.accept(() -> {
            if (!player.isOnline() || !(player.getVehicle() instanceof RideableMinecart cart)) {
                return;
            }
            settle(cart);
            if (!track.driven(cart) && track.onNetwork(cart)) {
                track.openRide(player);
            }
        });
    }

    /** A cart that a crash left without gravity falls again when its chunk loads. */
    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        event.getEntities().forEach(this::settle);
    }

    /** Gives a cart that is not driven its vanilla physics back, when a ride ended without doing so. */
    private void settle(final Entity entity) {
        if (entity instanceof RideableMinecart cart && !cart.hasGravity() && !track.driven(cart)) {
            Rides.standStill(cart);
        }
    }
}
