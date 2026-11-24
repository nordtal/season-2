package eu.nordtal.season.smp.area;

import eu.nordtal.season.database.inbox.AreaShape;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.capture.CaptureMode;
import eu.nordtal.season.smp.capture.CaptureSessions;
import eu.nordtal.season.smp.feedback.WorldEffects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The area editor in the game: an admin's tool and handles for the corners, drawn for that admin alone.
 * A right click moves a handle or adds a corner on an edge, and a sneaking right click removes a corner.
 * Requests run on the hub's thread and editing on the main thread.
 */
public final class AreaEditor implements Listener {

    public static final DocumentSpec<DocumentSpec.None> TOOL = DocumentSpec.game(
                    "smp", "area-editor-tool", "Area editor", DocumentSpec.Shape.ACTION_BAR)
            .titled("Area editor tool");

    public static final DocumentSpec<DocumentSpec.None> HOW = DocumentSpec.game(
                    "smp", "area-editor-how", "Area editor", DocumentSpec.Shape.ACTION_BAR)
            .titled("How the area editor works");

    public static final DocumentSpec<DocumentSpec.None> CARRYING = DocumentSpec.game(
                    "smp", "area-editor-carrying", "Area editor", DocumentSpec.Shape.ACTION_BAR)
            .titled("Handle picked up");

    public static final DocumentSpec<DocumentSpec.None> KEEPS_THREE = DocumentSpec.game(
                    "smp", "area-editor-keeps-three", "Area editor", DocumentSpec.Shape.ACTION_BAR)
            .titled("An area keeps three corners");

    /** How far a click on a block reaches to an edge, in blocks. */
    static final double EDGE_REACH = 1.5;

    /** How far the handles and the edges are drawn around the admin, in blocks. */
    static final double SIGHT = 64;

    private static final int REACH = 64;
    private static final long WAIT_SECONDS = 5;

    private final Plugin plugin;
    private final Inbox<SmpRequest> inbox;
    private final WorldEffects effects;
    private final BiFunction<Player, DocumentSpec<DocumentSpec.None>, Component> text;
    private final Predicate<String> overworld;
    private final CaptureSessions captureSessions;
    private final NamespacedKey toolKey;
    private final Map<UUID, Session> sessions = new HashMap<>();

    /**
     * @param text renders a document in the language of the player it is for
     * @param overworld whether a world's name is the overworld's, whose map ends at the edges {@link Polygons} names
     */
    public AreaEditor(
            final Plugin plugin,
            final Inbox<SmpRequest> inbox,
            final WorldEffects effects,
            final BiFunction<Player, DocumentSpec<DocumentSpec.None>, Component> text,
            final Predicate<String> overworld,
            final CaptureSessions captureSessions) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.text = Objects.requireNonNull(text, "text");
        this.overworld = Objects.requireNonNull(overworld, "overworld");
        this.captureSessions = Objects.requireNonNull(captureSessions, "captureSessions");
        this.toolKey = new NamespacedKey(plugin, "area-editor");
    }

    /** What a handle moves: a corner, or the bottom or the top of a shape with heights. */
    private enum Kind {
        CORNER,
        BOTTOM,
        TOP
    }

    /** Which handle of the draft: the shape, the corner for a corner handle, and the kind. */
    private record Key(int shape, int corner, Kind kind) {}

    private record Handle(BlockDisplay display, Interaction hitbox) {
        boolean valid() {
            return display.isValid() && hitbox.isValid();
        }

        void remove() {
            display.remove();
            hitbox.remove();
        }
    }

    private static final class Session {
        private final AreaDraft draft;
        private final Map<Key, Handle> handles = new HashMap<>();
        private @Nullable Key carried;
        private int clickedAt = -1;

        private Session(final AreaDraft draft) {
            this.draft = draft;
        }
    }

    /** Opens the editor for {@code edit}'s player with a draft of its shapes, refusing if another capture is active. */
    public Answer open(final SmpRequest.EditAreas edit) {
        return onMain(() -> {
            final Player admin = plugin.getServer().getPlayer(edit.player().value());
            if (admin == null) {
                return Answer.refused(ServerRefusal.NOT_HERE.with());
            }
            if (plugin.getServer().getWorld(edit.world()) == null) {
                throw new IllegalStateException("the world of an area edit is not loaded: world=" + edit.world());
            }
            if (!captureSessions.tryBegin(admin.getUniqueId(), CaptureMode.AREA_EDITOR)) {
                return Answer.refused(ServerRefusal.CAPTURE_ACTIVE.with());
            }
            final int slot = freeHotbarSlot(admin.getInventory());
            if (slot < 0) {
                captureSessions.end(admin.getUniqueId(), CaptureMode.AREA_EDITOR);
                return Answer.refused(ServerRefusal.HOTBAR_FULL.with());
            }
            admin.getInventory().setItem(slot, tool(admin));
            admin.getInventory().setHeldItemSlot(slot);
            final Session session = new Session(new AreaDraft(edit.world(), edit.heights(), edit.shapes()));
            sessions.put(admin.getUniqueId(), session);
            sync(admin, session);
            admin.sendActionBar(text.apply(admin, HOW));
            return Answer.done(Sent.of(HOW));
        });
    }

    /**
     * Carries out a step of {@code step}'s player's draft and settles {@code request} with the draft after it.
     * Refused while the player is not here or holds no draft.
     */
    public Answer step(final long request, final SmpRequest.AreaDraft step) {
        final Object found = onMain(() -> {
            final Player admin = plugin.getServer().getPlayer(step.player().value());
            if (admin == null) {
                return ServerRefusal.NOT_HERE;
            }
            final Session session = sessions.get(admin.getUniqueId());
            if (session == null) {
                return ServerRefusal.NO_AREA_DRAFT;
            }
            return switch (step.step()) {
                case ADD -> {
                    final Location at = Objects.requireNonNull(admin.getLocation());
                    if (!at.getWorld().getName().equals(session.draft.world())) {
                        yield ServerRefusal.WRONG_WORLD;
                    }
                    session.draft.add(step.name(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
                    sync(admin, session);
                    yield new SmpRequest.AreaDraft.Answer(session.draft.world(), session.draft.shapes());
                }
                case READ -> new SmpRequest.AreaDraft.Answer(session.draft.world(), session.draft.shapes());
                case CLOSE -> {
                    final SmpRequest.AreaDraft.Answer answer =
                            new SmpRequest.AreaDraft.Answer(session.draft.world(), session.draft.shapes());
                    close(admin);
                    yield answer;
                }
            };
        });
        if (found instanceof final ServerRefusal refusal) {
            return Answer.refused(refusal.with());
        }
        if (inbox.settle(request, Outcome.done(found)).isEmpty()) {
            plugin.getLogger().info("area draft request no longer running, draft not recorded: request=" + request);
        }
        return Answer.open();
    }

    /** Takes every handle and tool away, as the plugin stops. Main thread only. */
    public void stop() {
        for (final UUID id : List.copyOf(sessions.keySet())) {
            final Player admin = plugin.getServer().getPlayer(id);
            if (admin != null) {
                close(admin);
            } else {
                Objects.requireNonNull(sessions.remove(id)).handles.values().forEach(Handle::remove);
                captureSessions.end(id, CaptureMode.AREA_EDITOR);
            }
        }
    }

    /** Moves a carried handle along with its admin's look, and draws the edges. Main thread, once a tick. */
    public void tick(final int tick) {
        for (final Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            final Player admin = plugin.getServer().getPlayer(entry.getKey());
            if (admin == null) {
                continue;
            }
            final Session session = entry.getValue();
            if (session.carried != null) {
                follow(admin, session, session.carried);
                if (tick % 20 == 0) {
                    admin.sendActionBar(text.apply(admin, CARRYING));
                }
            }
            if (tick % 20 == 0) {
                sync(admin, session);
            }
            if (tick % 5 == 0) {
                drawEdges(admin, session);
            }
        }
    }

    private void close(final Player admin) {
        final Session session = sessions.remove(admin.getUniqueId());
        if (session != null) {
            session.handles.values().forEach(Handle::remove);
        }
        captureSessions.end(admin.getUniqueId(), CaptureMode.AREA_EDITOR);
        removeTools(admin);
    }

    @EventHandler
    public void quit(final PlayerQuitEvent event) {
        close(event.getPlayer());
    }

    /** A tool left from a draft that ended with the server is taken away. */
    @EventHandler
    public void joined(final PlayerJoinEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) {
            removeTools(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void dropped(final PlayerDropItemEvent event) {
        if (isTool(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void died(final PlayerDeathEvent event) {
        final List<ItemStack> tools = new ArrayList<>();
        event.getDrops().removeIf(item -> isTool(item) && tools.add(item));
        // An admin who dies while editing keeps the tool, so the unsaved draft stays reachable.
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.getItemsToKeep().addAll(tools);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void clickedInventory(final InventoryClickEvent event) {
        final boolean inTop = event.getClickedInventory() != null
                && !event.getClickedInventory().equals(event.getView().getBottomInventory());
        if (inTop
                && (isTool(event.getCursor())
                        || (event.getClick() == ClickType.NUMBER_KEY
                                && isTool(event.getWhoClicked().getInventory().getItem(event.getHotbarButton())))
                        || (event.getClick() == ClickType.SWAP_OFFHAND
                                && isTool(event.getWhoClicked().getInventory().getItemInOffHand())))) {
            event.setCancelled(true);
        } else if ((isTool(event.getCursor()) && isBundle(event.getCurrentItem()))
                || (isTool(event.getCurrentItem()) && isBundle(event.getCursor()))) {
            event.setCancelled(true);
        } else if (event.isShiftClick()
                && isTool(event.getCurrentItem())
                && !(event.getView().getTopInventory() instanceof CraftingInventory
                        && event.getView().getTopInventory().getSize() == 5)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void draggedInventory(final InventoryDragEvent event) {
        if (!isTool(event.getOldCursor())) {
            return;
        }
        final int topSize = event.getView().getTopInventory().getSize();
        for (final int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void manipulatedStand(final PlayerArmorStandManipulateEvent event) {
        if (isTool(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void movedItem(final InventoryMoveItemEvent event) {
        if (isTool(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void broke(final BlockBreakEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())
                && isTool(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void clickedHandle(final PlayerInteractEntityEvent event) {
        final Player admin = event.getPlayer();
        if (!(event.getRightClicked() instanceof Interaction)) {
            // An item frame, an allay or a golem would take the tool from the admin's hand.
            if (isTool(admin.getInventory().getItem(event.getHand()))) {
                event.setCancelled(true);
            }
            return;
        }
        final Session session = sessions.get(admin.getUniqueId());
        if (session == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Key key = keyOf(session, event.getRightClicked());
        if (key == null) {
            return;
        }
        event.setCancelled(true);
        session.clickedAt = admin.getServer().getCurrentTick();
        if (session.carried != null) {
            setDown(admin, session);
        } else if (admin.isSneaking() && key.kind() == Kind.CORNER) {
            if (session.draft.remove(key.shape(), key.corner())) {
                sync(admin, session);
            } else {
                admin.sendActionBar(text.apply(admin, KEEPS_THREE));
            }
        } else if (isTool(admin.getInventory().getItemInMainHand())) {
            session.carried = key;
            admin.sendActionBar(text.apply(admin, CARRYING));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void clicked(final PlayerInteractEvent event) {
        final Player admin = event.getPlayer();
        final Session session = sessions.get(admin.getUniqueId());
        if (session == null || event.getHand() != EquipmentSlot.HAND || !isTool(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // A click on a handle also reaches here in the same tick.
        if (session.clickedAt == admin.getServer().getCurrentTick()) {
            return;
        }
        if (session.carried != null) {
            setDown(admin, session);
            return;
        }
        final Block seen = admin.getTargetBlockExact(REACH);
        if (seen == null || !seen.getWorld().getName().equals(session.draft.world())) {
            return;
        }
        for (int shape = 0; shape < session.draft.size(); shape++) {
            final int edge =
                    Polygons.nearestEdge(session.draft.shape(shape).corners(), seen.getX(), seen.getZ(), EDGE_REACH);
            if (edge >= 0) {
                session.carried =
                        new Key(shape, session.draft.insert(shape, edge, seen.getX(), seen.getZ()), Kind.CORNER);
                sync(admin, session);
                admin.sendActionBar(text.apply(admin, CARRYING));
                return;
            }
        }
    }

    private void setDown(final Player admin, final Session session) {
        session.carried = null;
        sync(admin, session);
        admin.sendActionBar(text.apply(admin, HOW));
    }

    /** Moves the carried handle to where the admin looks: a corner to the block, a height along its column. */
    private void follow(final Player admin, final Session session, final Key key) {
        if (!admin.getWorld().getName().equals(session.draft.world())) {
            return;
        }
        if (key.kind() == Kind.CORNER) {
            final Block seen = admin.getTargetBlockExact(REACH);
            if (seen != null) {
                session.draft.move(key.shape(), key.corner(), seen.getX(), seen.getZ());
            }
        } else {
            final Location eye = admin.getEyeLocation();
            final var look = eye.getDirection();
            final double[] centre = centre(session.draft.shape(key.shape()));
            final double y = Polygons.heightAt(
                    new double[] {eye.getX(), eye.getY(), eye.getZ()},
                    new double[] {look.getX(), look.getY(), look.getZ()},
                    centre[0],
                    centre[1]);
            final World world = admin.getWorld();
            final int block = (int) Math.clamp(Math.floor(y), world.getMinHeight(), world.getMaxHeight() - 1);
            session.draft.height(key.shape(), key.kind() == Kind.TOP, block);
        }
        sync(admin, session);
    }

    /** Spawns the handles near the admin that are missing, moves those whose place changed and removes the rest. */
    private void sync(final Player admin, final Session session) {
        final World world = plugin.getServer().getWorld(session.draft.world());
        final Map<Key, Location> wanted = new HashMap<>();
        if (world != null && admin.getWorld().equals(world)) {
            final Location at = Objects.requireNonNull(admin.getLocation());
            final double eyeY = admin.getEyeLocation().getY();
            for (int shape = 0; shape < session.draft.size(); shape++) {
                final AreaShape area = session.draft.shape(shape);
                final double cornerY = cornerHeight(area, eyeY);
                for (int corner = 0; corner < area.corners().size(); corner++) {
                    final AreaShape.Corner point = area.corners().get(corner);
                    final double y = session.draft.heights() ? cornerY : ground(world, point.x(), point.z(), at);
                    wanted.put(
                            new Key(shape, corner, Kind.CORNER),
                            new Location(world, point.x() + 0.5, y, point.z() + 0.5));
                }
                if (session.draft.heights()) {
                    final double[] centre = centre(area);
                    final double topY = topEdge(area);
                    final int cx = (int) Math.floor(centre[0]);
                    final int cz = (int) Math.floor(centre[1]);
                    final double bottomY = world.isChunkLoaded(cx >> 4, cz >> 4)
                            ? bottomHandleY(
                                    area.bottom(),
                                    topY,
                                    world.getMaxHeight(),
                                    y -> world.getBlockAt(cx, y, cz).isPassable())
                            : area.bottom();
                    wanted.put(new Key(shape, -1, Kind.BOTTOM), new Location(world, centre[0], bottomY, centre[1]));
                    wanted.put(new Key(shape, -1, Kind.TOP), new Location(world, centre[0], topY, centre[1]));
                }
            }
            wanted.values()
                    .removeIf(place -> !withinSight(place.getX(), place.getZ(), at.getX(), at.getZ())
                            || !world.isChunkLoaded(place.getBlockX() >> 4, place.getBlockZ() >> 4));
        }
        session.handles.entrySet().removeIf(entry -> {
            if (wanted.containsKey(entry.getKey()) && entry.getValue().valid()) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
        for (final Map.Entry<Key, Location> entry : wanted.entrySet()) {
            final Handle handle = session.handles.get(entry.getKey());
            final boolean carried = entry.getKey().equals(session.carried);
            if (handle == null) {
                session.handles.put(
                        entry.getKey(),
                        spawn(admin, entry.getValue(), entry.getKey().kind(), carried));
            } else {
                if (!handle.display().getLocation().equals(entry.getValue())) {
                    handle.display().teleport(entry.getValue());
                    handle.hitbox().teleport(entry.getValue().clone().subtract(0, 0.4, 0));
                }
                handle.display().setGlowColorOverride(glow(entry.getKey().kind(), carried));
            }
        }
    }

    /** The top face height of a 3D area, shared by its top handle, corner clamp and top dust ring. */
    static double topEdge(final AreaShape area) {
        return area.top() + 1;
    }

    /** The height of a 3D area's corner handles: the admin's eye height, clamped to the area's vertical span. */
    static double cornerHeight(final AreaShape area, final double eyeY) {
        final double top = topEdge(area);
        return Math.clamp(eyeY, Math.min(area.bottom(), top), Math.max(area.bottom(), top));
    }

    /** The height of a 3D area's bottom handle: the first passable block at or above {@code bottom}. */
    static double bottomHandleY(
            final int bottom, final double topEdge, final int worldMaxHeight, final IntPredicate passable) {
        final int limit = Math.max(bottom, worldMaxHeight - 1);
        int y = bottom;
        while (y < limit && !passable.test(y)) {
            y++;
        }
        if (Math.abs(y - topEdge) < 0.9) {
            return topEdge + 1.0;
        }
        return y;
    }

    /** Whether two places lie within horizontal sight of each other. */
    static boolean withinSight(final double x1, final double z1, final double x2, final double z2) {
        final double dx = x1 - x2;
        final double dz = z1 - z2;
        return dx * dx + dz * dz <= SIGHT * SIGHT;
    }

    /** The block above the ground at a corner, or the admin's height where the ground is not loaded. */
    private static double ground(final World world, final int x, final int z, final Location admin) {
        return world.isChunkLoaded(x >> 4, z >> 4) ? world.getHighestBlockYAt(x, z) + 1 : admin.getY();
    }

    private static double[] centre(final AreaShape shape) {
        double x = 0;
        double z = 0;
        for (final AreaShape.Corner corner : shape.corners()) {
            x += corner.x() + 0.5;
            z += corner.z() + 0.5;
        }
        final int n = Math.max(1, shape.corners().size());
        return new double[] {x / n, z / n};
    }

    private static Color glow(final Kind kind, final boolean carried) {
        if (carried) {
            return Color.WHITE;
        }
        return kind == Kind.CORNER ? Color.AQUA : kind == Kind.TOP ? Color.LIME : Color.ORANGE;
    }

    private Handle spawn(final Player admin, final Location at, final Kind kind, final boolean carried) {
        final World world = at.getWorld();
        final BlockDisplay display = world.spawn(at, BlockDisplay.class, block -> {
            block.setBlock((kind == Kind.CORNER ? Material.SEA_LANTERN : Material.OCHRE_FROGLIGHT).createBlockData());
            block.setPersistent(false);
            block.setVisibleByDefault(false);
            block.setGlowing(true);
            block.setGlowColorOverride(glow(kind, carried));
            block.setBrightness(new Display.Brightness(15, 15));
            // A BlockDisplay is drawn from its corner, so the translation centres the half block on the handle.
            block.setTransformation(new Transformation(
                    new Vector3f(-0.25f, -0.25f, -0.25f),
                    new AxisAngle4f(),
                    new Vector3f(0.5f, 0.5f, 0.5f),
                    new AxisAngle4f()));
        });
        final Interaction hitbox = world.spawn(at.clone().subtract(0, 0.4, 0), Interaction.class, box -> {
            box.setInteractionWidth(0.8f);
            box.setInteractionHeight(0.8f);
            box.setResponsive(true);
            box.setPersistent(false);
            box.setVisibleByDefault(false);
        });
        admin.showEntity(plugin, display);
        admin.showEntity(plugin, hitbox);
        return new Handle(display, hitbox);
    }

    private static @Nullable Key keyOf(final Session session, final Entity clicked) {
        for (final Map.Entry<Key, Handle> entry : session.handles.entrySet()) {
            if (entry.getValue().hitbox().equals(clicked)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Draws each edge near the admin as dust, red while its shape could not be saved. */
    private void drawEdges(final Player admin, final Session session) {
        if (!admin.getWorld().getName().equals(session.draft.world())) {
            return;
        }
        final Location at = Objects.requireNonNull(admin.getLocation());
        final boolean inOverworld = overworld.test(session.draft.world());
        for (int shape = 0; shape < session.draft.size(); shape++) {
            final AreaShape area = session.draft.shape(shape);
            final boolean valid = Polygons.problem(area, session.draft.heights(), inOverworld) == null;
            final List<double[]> rings = new ArrayList<>();
            if (session.draft.heights()) {
                rings.add(new double[] {area.bottom()});
                rings.add(new double[] {topEdge(area)});
            } else {
                rings.add(new double[] {Double.NaN});
            }
            final List<AreaShape.Corner> corners = area.corners();
            for (int i = 0; i < corners.size(); i++) {
                final AreaShape.Corner from = corners.get(i);
                final AreaShape.Corner to = corners.get((i + 1) % corners.size());
                for (final double[] ring : rings) {
                    final double fromY =
                            Double.isNaN(ring[0]) ? ground(admin.getWorld(), from.x(), from.z(), at) : ring[0];
                    final double toY = Double.isNaN(ring[0]) ? ground(admin.getWorld(), to.x(), to.z(), at) : ring[0];
                    line(admin, valid, at, from.x() + 0.5, fromY, from.z() + 0.5, to.x() + 0.5, toY, to.z() + 0.5);
                }
                if (session.draft.heights()) {
                    line(
                            admin,
                            valid,
                            at,
                            from.x() + 0.5,
                            area.bottom(),
                            from.z() + 0.5,
                            from.x() + 0.5,
                            topEdge(area),
                            from.z() + 0.5);
                }
            }
        }
    }

    /** Draws the part of a line within sight of the admin, a dust every half block. */
    private void line(
            final Player admin,
            final boolean valid,
            final Location at,
            final double x1,
            final double y1,
            final double z1,
            final double x2,
            final double y2,
            final double z2) {
        final double length = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        if (length == 0) {
            return;
        }
        final double nearest = Math.clamp(
                ((at.getX() - x1) * (x2 - x1) + (at.getY() - y1) * (y2 - y1) + (at.getZ() - z1) * (z2 - z1)) / length,
                0,
                length);
        final double gap = Math.sqrt(Math.pow(x1 + (x2 - x1) * nearest / length - at.getX(), 2)
                + Math.pow(y1 + (y2 - y1) * nearest / length - at.getY(), 2)
                + Math.pow(z1 + (z2 - z1) * nearest / length - at.getZ(), 2));
        if (gap > SIGHT) {
            return;
        }
        final double half = Math.sqrt(SIGHT * SIGHT - gap * gap);
        for (double s = Math.max(0, nearest - half); s <= Math.min(length, nearest + half); s += 0.5) {
            effects.areaEdgePoint(
                    admin,
                    x1 + (x2 - x1) * s / length,
                    y1 + (y2 - y1) * s / length,
                    z1 + (z2 - z1) * s / length,
                    valid);
        }
    }

    private ItemStack tool(final Player admin) {
        final ItemStack tool = new ItemStack(Material.BREEZE_ROD);
        tool.editMeta(meta -> {
            meta.customName(text.apply(admin, TOOL));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        });
        return tool;
    }

    private static boolean isBundle(final @Nullable ItemStack item) {
        return item != null && Tag.ITEMS_BUNDLES.isTagged(item.getType());
    }

    private boolean isTool(final @Nullable ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && stack.getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }

    private void removeTools(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isTool(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
        if (isTool(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    /** Returns the held slot when it is empty, else the first empty hotbar slot, or -1 when the hotbar is full. */
    static int freeHotbarSlot(final PlayerInventory inventory) {
        final ItemStack held = inventory.getItem(inventory.getHeldItemSlot());
        if (held == null || held.isEmpty()) {
            return inventory.getHeldItemSlot();
        }
        for (int slot = 0; slot < 9; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private <T> T onMain(final Supplier<T> work) {
        final CompletableFuture<T> done =
                CompletableFuture.supplyAsync(work, PaperScheduler.of(plugin).mainThread());
        try {
            return done.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final ExecutionException | TimeoutException failed) {
            throw new IllegalStateException("the area editor could not be reached", failed);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
