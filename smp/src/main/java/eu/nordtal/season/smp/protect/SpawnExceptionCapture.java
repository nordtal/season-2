package eu.nordtal.season.smp.protect;

import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.inbox.SpawnExceptionBlock;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.capture.CaptureFeedback;
import eu.nordtal.season.smp.capture.CaptureMode;
import eu.nordtal.season.smp.capture.CaptureSessions;
import eu.nordtal.season.smp.region.PolygonAreas;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * In-game capture session for selecting spawn exception doors and trapdoors.
 * Right-clicking toggles a hand-openable door or trapdoor inside spawn without toggling the block itself.
 */
public final class SpawnExceptionCapture implements Listener {

    public static final DocumentSpec<DocumentSpec.None> HOW = DocumentSpec.game(
                    "smp", "spawn-exceptions-how", "Spawn exceptions", DocumentSpec.Shape.ACTION_BAR)
            .titled("How spawn exception selection works");

    public static final DocumentSpec<DocumentSpec.None> ADDED = DocumentSpec.game(
                    "smp", "spawn-exceptions-added", "Spawn exceptions", DocumentSpec.Shape.ACTION_BAR)
            .titled("Spawn exception added");

    public static final DocumentSpec<DocumentSpec.None> REMOVED = DocumentSpec.game(
                    "smp", "spawn-exceptions-removed", "Spawn exceptions", DocumentSpec.Shape.ACTION_BAR)
            .titled("Spawn exception removed");

    public static final DocumentSpec<DocumentSpec.None> OUTSIDE = DocumentSpec.game(
                    "smp", "spawn-exceptions-outside", "Spawn exceptions", DocumentSpec.Shape.ACTION_BAR)
            .titled("Block outside spawn");

    public static final DocumentSpec<DocumentSpec.None> INVALID = DocumentSpec.game(
                    "smp", "spawn-exceptions-invalid", "Spawn exceptions", DocumentSpec.Shape.ACTION_BAR)
            .titled("Invalid spawn exception block");

    private static final long WAIT_SECONDS = 5;

    private final Plugin plugin;
    private final Inbox<SmpRequest> inbox;
    private final Supplier<PolygonAreas> regions;
    private final CaptureSessions captureSessions;
    private final CaptureFeedback feedback;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private static final class Session {
        private final Map<SpawnExceptions.BlockPos, SpawnExceptionBlock> draft = new LinkedHashMap<>();
        private final Map<SpawnExceptions.BlockPos, BlockDisplay> highlights = new HashMap<>();
    }

    public SpawnExceptionCapture(
            final Plugin plugin,
            final Inbox<SmpRequest> inbox,
            final Supplier<PolygonAreas> regions,
            final CaptureSessions captureSessions,
            final CaptureFeedback feedback) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.captureSessions = Objects.requireNonNull(captureSessions, "captureSessions");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    /** Starts a spawn exception capture session for {@code edit}'s player. Refused if offline or busy. */
    public Answer open(final SmpRequest.EditSpawnExceptions edit) {
        return onMain(() -> {
            final Player admin = plugin.getServer().getPlayer(edit.player().value());
            if (admin == null) {
                return Answer.refused(ServerRefusal.NOT_HERE.with());
            }
            if (!captureSessions.tryBegin(admin.getUniqueId(), CaptureMode.SPAWN_EXCEPTIONS)) {
                return Answer.refused(ServerRefusal.CAPTURE_ACTIVE.with());
            }
            final Session session = new Session();
            for (final SpawnExceptionBlock entry : edit.entries()) {
                final SpawnExceptions.BlockPos pos =
                        new SpawnExceptions.BlockPos(entry.world(), entry.x(), entry.y(), entry.z());
                session.draft.put(pos, entry);
                spawnHighlight(admin, session, pos);
            }
            sessions.put(admin.getUniqueId(), session);
            feedback.tell(admin, HOW, Feedback.SMALL_SUCCESS);
            return Answer.done(Sent.of(HOW));
        });
    }

    /** Reads or closes the admin's spawn exception draft and settles {@code request} with its entries. */
    public Answer step(final long request, final SmpRequest.SpawnExceptionDraft step) {
        final Object found = onMain(() -> {
            final Player admin = plugin.getServer().getPlayer(step.player().value());
            if (admin == null) {
                return ServerRefusal.NOT_HERE;
            }
            final Session session = sessions.get(admin.getUniqueId());
            if (session == null) {
                return ServerRefusal.NO_SPAWN_EXCEPTION_DRAFT;
            }
            final SmpRequest.SpawnExceptionDraft.Answer answer =
                    new SmpRequest.SpawnExceptionDraft.Answer(List.copyOf(session.draft.values()));
            if (step.step() == SmpRequest.SpawnExceptionDraft.Step.CLOSE) {
                close(admin);
            }
            return answer;
        });
        if (found instanceof final ServerRefusal refusal) {
            return Answer.refused(refusal.with());
        }
        if (inbox.settle(request, Outcome.done(found)).isEmpty()) {
            plugin.getLogger()
                    .info("spawn exception draft request no longer running, draft not recorded: request=" + request);
        }
        return Answer.open();
    }

    /** Cleans up all active capture sessions on plugin disable. Main thread only. */
    public void stop() {
        for (final UUID id : List.copyOf(sessions.keySet())) {
            final Player admin = plugin.getServer().getPlayer(id);
            if (admin != null) {
                close(admin);
            } else {
                final Session removed = sessions.remove(id);
                if (removed != null) {
                    removed.highlights.values().forEach(BlockDisplay::remove);
                }
                captureSessions.end(id, CaptureMode.SPAWN_EXCEPTIONS);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        close(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        final Player admin = event.getPlayer();
        final Session session = sessions.get(admin.getUniqueId());
        if (session == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Block anchor = SpawnExceptions.normalize(event.getClickedBlock());
        if (!regions.get().containsBlock(anchor.getWorld().getName(), anchor.getX(), anchor.getY(), anchor.getZ())) {
            feedback.tell(admin, OUTSIDE, Feedback.REFUSED);
            return;
        }
        if (!SpawnProtectionRules.isHandOpenableDoorOrTrapdoor(anchor.getType())) {
            feedback.tell(admin, INVALID, Feedback.REFUSED);
            return;
        }
        final SpawnExceptions.BlockPos pos = SpawnExceptions.positionOf(anchor);
        if (session.draft.remove(pos) != null) {
            final BlockDisplay removed = session.highlights.remove(pos);
            if (removed != null) {
                removed.remove();
            }
            feedback.tell(admin, REMOVED, Feedback.SMALL_SUCCESS);
            return;
        }
        final SpawnExceptionBlock captured = capture(anchor);
        session.draft.put(pos, captured);
        spawnHighlight(admin, session, pos);
        feedback.tell(admin, ADDED, Feedback.SMALL_SUCCESS);
    }

    private static SpawnExceptionBlock capture(final Block anchor) {
        final SpawnExceptionBlock.Kind kind = SpawnProtectionRules.isHandOpenableDoor(anchor.getType())
                ? SpawnExceptionBlock.Kind.DOOR
                : SpawnExceptionBlock.Kind.TRAPDOOR;
        final BlockFace face = anchor.getBlockData() instanceof final Directional directional
                ? directional.getFacing()
                : BlockFace.NORTH;
        final SpawnExceptionBlock.Facing facing = switch (face) {
            case SOUTH -> SpawnExceptionBlock.Facing.SOUTH;
            case EAST -> SpawnExceptionBlock.Facing.EAST;
            case WEST -> SpawnExceptionBlock.Facing.WEST;
            default -> SpawnExceptionBlock.Facing.NORTH;
        };
        final boolean open = anchor.getBlockData() instanceof final Openable openable && openable.isOpen();
        return new SpawnExceptionBlock(
                anchor.getWorld().getName(), anchor.getX(), anchor.getY(), anchor.getZ(), kind, facing, open);
    }

    private void spawnHighlight(final Player admin, final Session session, final SpawnExceptions.BlockPos pos) {
        final World world = Bukkit.getWorld(pos.world());
        if (world == null || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
            return;
        }
        final Location at = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
        final BlockDisplay display = world.spawn(at, BlockDisplay.class, block -> {
            block.setBlock(Material.LIME_STAINED_GLASS.createBlockData());
            block.setPersistent(false);
            block.setVisibleByDefault(false);
            block.setGlowing(true);
            block.setGlowColorOverride(Color.LIME);
            block.setBrightness(new Display.Brightness(15, 15));
            block.setTransformation(new Transformation(
                    new Vector3f(-0.25f, -0.25f, -0.25f),
                    new AxisAngle4f(),
                    new Vector3f(0.5f, 0.5f, 0.5f),
                    new AxisAngle4f()));
        });
        admin.showEntity(plugin, display);
        session.highlights.put(pos, display);
    }

    private void close(final Player admin) {
        final Session removed = sessions.remove(admin.getUniqueId());
        if (removed != null) {
            removed.highlights.values().forEach(BlockDisplay::remove);
        }
        captureSessions.end(admin.getUniqueId(), CaptureMode.SPAWN_EXCEPTIONS);
    }

    private <T> T onMain(final Supplier<T> work) {
        final CompletableFuture<T> done =
                CompletableFuture.supplyAsync(work, PaperScheduler.of(plugin).mainThread());
        try {
            return done.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final ExecutionException | TimeoutException failed) {
            throw new IllegalStateException("spawn exception capture could not be reached", failed);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
