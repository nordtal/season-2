package eu.nordtal.season.smp.protect;

import eu.nordtal.season.packrendering.WorldMarkers;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.config.SpawnExceptionSpec;
import eu.nordtal.season.smp.config.SpawnExceptionsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Manages configured spawn door and trapdoor exceptions, their display-entity markers and auto-reset timers.
 * Two-block doors are normalized to their lower half so both halves act as one entry.
 */
public final class SpawnExceptions implements Listener {

    private static final Color TRANSPARENT = Color.fromARGB(0, 0, 0, 0);

    /** Normalized block coordinates in a named world. */
    public record BlockPos(String world, int x, int y, int z) {}

    /** Active runtime configuration of one exception entry. */
    record Entry(
            BlockPos pos,
            boolean defaultOpen,
            SpawnExceptionSpec.ResetMode resetMode,
            Duration resetDelay,
            SpawnExceptionSpec.IconSide iconSide) {

        boolean sameTimerConfig(final Entry other) {
            return defaultOpen == other.defaultOpen
                    && resetMode == other.resetMode
                    && resetDelay.equals(other.resetDelay);
        }
    }

    private final Plugin plugin;
    private final Clock clock;
    private final SmpSounds sounds;
    private volatile Map<BlockPos, Entry> entries = Map.of();
    private final Map<BlockPos, List<TextDisplay>> markers = new HashMap<>();
    private final Map<BlockPos, Instant> deadlines = new HashMap<>();
    private final Set<BlockPos> pendingStartResets = new HashSet<>();

    public SpawnExceptions(final Plugin plugin, final Clock clock, final SmpSounds sounds) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sounds = Objects.requireNonNull(sounds, "sounds");
    }

    /** Normalizes a door's upper half to its lower half; all other blocks return themselves. */
    static Block normalize(final Block block) {
        if (block.getBlockData() instanceof final Door door && door.getHalf() == Bisected.Half.TOP) {
            return block.getRelative(BlockFace.DOWN);
        }
        return block;
    }

    /** Returns the normalized {@link BlockPos} for {@code block}. */
    static BlockPos positionOf(final Block block) {
        final Block anchor = normalize(block);
        return new BlockPos(anchor.getWorld().getName(), anchor.getX(), anchor.getY(), anchor.getZ());
    }

    /** Returns true when {@code block} is a configured, still-valid hand-openable door or trapdoor exception. */
    public boolean isAllowed(final Block block) {
        if (!SpawnProtectionRules.isHandOpenableDoorOrTrapdoor(block.getType())) {
            return false;
        }
        return entries.containsKey(positionOf(block));
    }

    /** Loads initial entries at server start, resetting all timed entries to their default state. Main thread only. */
    public void start(final SpawnExceptionsSpec spec) {
        entries = index(spec);
        pendingStartResets.clear();
        for (final Entry entry : entries.values()) {
            if (entry.resetMode() == SpawnExceptionSpec.ResetMode.AUTO_RESET) {
                pendingStartResets.add(entry.pos());
            }
        }
        syncAllLoaded();
    }

    /** Applies a live config reload, keeping running timers for unchanged entries. Main thread only. */
    public void apply(final SpawnExceptionsSpec spec) {
        final Map<BlockPos, Entry> previous = entries;
        final Map<BlockPos, Entry> next = index(spec);
        entries = next;
        for (final BlockPos removed : List.copyOf(markers.keySet())) {
            if (!next.containsKey(removed)) {
                removeMarkers(removed);
                deadlines.remove(removed);
                pendingStartResets.remove(removed);
            }
        }
        for (final Map.Entry<BlockPos, Entry> item : next.entrySet()) {
            final Entry old = previous.get(item.getKey());
            if (old == null || !old.sameTimerConfig(item.getValue())) {
                deadlines.remove(item.getKey());
                pendingStartResets.remove(item.getKey());
            }
        }
        syncAllLoaded();
    }

    /** Removes all spawned marker entities on plugin shutdown. Main thread only. */
    public void stop() {
        for (final List<TextDisplay> list : markers.values()) {
            list.forEach(TextDisplay::remove);
        }
        markers.clear();
        deadlines.clear();
        pendingStartResets.clear();
    }

    /** Checks auto-reset timers and resets any expired doors or trapdoors to their default state. Main thread only. */
    public void tick() {
        final Instant now = clock.instant();
        for (final Map.Entry<BlockPos, Instant> item : List.copyOf(deadlines.entrySet())) {
            if (now.isBefore(item.getValue())) {
                continue;
            }
            deadlines.remove(item.getKey());
            final Entry entry = entries.get(item.getKey());
            if (entry != null) {
                resetToDefault(entry, true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(final ChunkLoadEvent event) {
        final Chunk chunk = event.getChunk();
        final String world = chunk.getWorld().getName();
        for (final Entry entry : entries.values()) {
            if (entry.pos().world().equals(world)
                    && (entry.pos().x() >> 4) == chunk.getX()
                    && (entry.pos().z() >> 4) == chunk.getZ()) {
                if (pendingStartResets.remove(entry.pos())) {
                    resetToDefault(entry, false);
                }
                syncMarkers(entry);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(final ChunkUnloadEvent event) {
        final Chunk chunk = event.getChunk();
        final String world = chunk.getWorld().getName();
        for (final BlockPos pos : List.copyOf(markers.keySet())) {
            if (pos.world().equals(world) && (pos.x() >> 4) == chunk.getX() && (pos.z() >> 4) == chunk.getZ()) {
                removeMarkers(pos);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final BlockPos pos = positionOf(event.getBlock());
        if (entries.containsKey(pos)) {
            removeMarkers(pos);
            deadlines.remove(pos);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null
                || event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (!SpawnProtectionRules.isHandOpenableDoorOrTrapdoor(block.getType())) {
            return;
        }
        final BlockPos pos = positionOf(block);
        final Entry entry = entries.get(pos);
        if (entry == null) {
            return;
        }
        // Vanilla toggles Openable after the event completes; inspect the resulting state on the next tick.
        PaperScheduler.of(plugin).onMainAfter(PaperScheduler.TICK, () -> afterToggle(pos));
    }

    private void afterToggle(final BlockPos pos) {
        final Entry entry = entries.get(pos);
        if (entry == null) {
            removeMarkers(pos);
            deadlines.remove(pos);
            return;
        }
        final Block anchor = loadedBlock(pos);
        if (anchor == null || !SpawnProtectionRules.isHandOpenableDoorOrTrapdoor(anchor.getType())) {
            removeMarkers(pos);
            deadlines.remove(pos);
            return;
        }
        if (anchor.getBlockData() instanceof final Openable openable) {
            updateTimer(entry, openable.isOpen());
        }
        syncMarkers(entry);
    }

    /** Updates or cancels the auto-reset deadline for {@code entry} given its current open state. */
    void updateTimer(final Entry entry, final boolean currentlyOpen) {
        if (entry.resetMode() != SpawnExceptionSpec.ResetMode.AUTO_RESET) {
            deadlines.remove(entry.pos());
            return;
        }
        if (currentlyOpen == entry.defaultOpen()) {
            deadlines.remove(entry.pos());
        } else {
            deadlines.put(entry.pos(), clock.instant().plus(entry.resetDelay()));
        }
    }

    /** Returns the current pending deadline for {@code pos}, for testing. */
    @Nullable
    Instant deadlineOf(final BlockPos pos) {
        return deadlines.get(pos);
    }

    private void syncAllLoaded() {
        for (final Entry entry : entries.values()) {
            final Block anchor = loadedBlock(entry.pos());
            if (anchor == null) {
                continue;
            }
            if (pendingStartResets.remove(entry.pos())) {
                resetToDefault(entry, false);
            }
            syncMarkers(entry);
        }
    }

    private void resetToDefault(final Entry entry, final boolean playSound) {
        final Block anchor = loadedBlock(entry.pos());
        if (anchor == null || !SpawnProtectionRules.isHandOpenableDoorOrTrapdoor(anchor.getType())) {
            removeMarkers(entry.pos());
            return;
        }
        if (!(anchor.getBlockData() instanceof final Openable openable) || openable.isOpen() == entry.defaultOpen()) {
            return;
        }
        openable.setOpen(entry.defaultOpen());
        anchor.setBlockData(openable, true);
        if (playSound) {
            sounds.playDoorToggle(anchor, entry.defaultOpen());
        }
        syncMarkers(entry);
    }

    private void syncMarkers(final Entry entry) {
        removeMarkers(entry.pos());
        final Block anchor = loadedBlock(entry.pos());
        if (anchor == null || !SpawnProtectionRules.isHandOpenableDoorOrTrapdoor(anchor.getType())) {
            return;
        }
        final List<MarkerGeometry.Placement> placements = placementsFor(anchor, entry);
        if (placements.isEmpty()) {
            return;
        }
        final World world = anchor.getWorld();
        final List<TextDisplay> spawned = new ArrayList<>(placements.size());
        for (final MarkerGeometry.Placement placement : placements) {
            final Location at = new Location(
                    world,
                    anchor.getX() + placement.dx(),
                    anchor.getY() + placement.dy(),
                    anchor.getZ() + placement.dz(),
                    placement.yaw(),
                    placement.pitch());
            spawned.add(spawnMarker(world, at));
        }
        markers.put(entry.pos(), spawned);
    }

    private static List<MarkerGeometry.Placement> placementsFor(final Block anchor, final Entry entry) {
        if (anchor.getBlockData() instanceof final Door door) {
            return MarkerGeometry.forDoor(
                    door.getFacing(), door.getHinge(), door.isOpen(), entry.defaultOpen(), entry.iconSide());
        }
        if (anchor.getBlockData() instanceof final TrapDoor trapdoor) {
            return MarkerGeometry.forTrapdoor(
                    trapdoor.getFacing(), trapdoor.getHalf(), trapdoor.isOpen(), entry.defaultOpen(), entry.iconSide());
        }
        return List.of();
    }

    private static TextDisplay spawnMarker(final World world, final Location at) {
        return world.spawn(at, TextDisplay.class, display -> {
            display.text(WorldMarkers.rightClick());
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setSeeThrough(false);
            display.setTextOpacity((byte) 180);
            display.setBackgroundColor(TRANSPARENT);
            display.setShadowed(false);
            display.setPersistent(false);
            display.setViewRange(MarkerGeometry.VIEW_RANGE);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setTransformation(new Transformation(
                    new Vector3f(0f, MarkerGeometry.GLYPH_Y_OFFSET, 0f),
                    new AxisAngle4f(),
                    new Vector3f(MarkerGeometry.SCALE, MarkerGeometry.SCALE, MarkerGeometry.SCALE),
                    new AxisAngle4f()));
        });
    }

    private void removeMarkers(final BlockPos pos) {
        final List<TextDisplay> existing = markers.remove(pos);
        if (existing != null) {
            existing.forEach(TextDisplay::remove);
        }
    }

    private static @Nullable Block loadedBlock(final BlockPos pos) {
        final World world = Bukkit.getWorld(pos.world());
        if (world == null || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
            return null;
        }
        return world.getBlockAt(pos.x(), pos.y(), pos.z());
    }

    static Map<BlockPos, Entry> index(final SpawnExceptionsSpec spec) {
        final Map<BlockPos, Entry> map = new HashMap<>();
        for (final SpawnExceptionSpec item : spec.entries()) {
            final BlockPos pos = new BlockPos(item.world(), item.x(), item.y(), item.z());
            final Duration delay = Duration.ofMinutes(item.resetMinutes()).plusSeconds(item.resetSeconds());
            map.put(
                    pos,
                    new Entry(
                            pos,
                            item.defaultState() == SpawnExceptionSpec.DefaultState.OPEN,
                            item.resetMode(),
                            delay,
                            item.iconSide()));
        }
        return Map.copyOf(map);
    }
}
