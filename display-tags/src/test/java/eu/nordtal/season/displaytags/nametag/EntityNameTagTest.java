package eu.nordtal.season.displaytags.nametag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.papercommon.hud.PillHologram;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** Tests tracking visibility, 64-block range, respawn recovery and vertical plate lift on {@link EntityNameTag}. */
class EntityNameTagTest {

    @Test
    void doesNotSpawnBeforeEntityIsTrackedAndSpawnsOnceTracked() {
        final World world = world("nordtal");
        final Entity entity = entity(world, 0, 64, 0, true);
        final UUID viewerId = UUID.randomUUID();
        final Player viewer = player(viewerId, world, 10, 64, 0, true, false);
        final AtomicBoolean tracked = new AtomicBoolean(false);
        final AtomicInteger spawns = new AtomicInteger();
        final AtomicInteger updates = new AtomicInteger();
        final AtomicInteger despawns = new AtomicInteger();
        final EntityNameTag tag = testTag(new AtomicReference<>(entity), tracked, spawns, updates, despawns);

        tag.syncViewer(entity, viewerId, tag.shouldBeVisibleTo(entity, viewer));
        assertEquals(0, spawns.get(), "untracked entity never spawns displays");
        assertFalse(tag.viewers.contains(viewerId), "untracked viewer is not marked visible");

        tracked.set(true);
        tag.syncViewer(entity, viewerId, tag.shouldBeVisibleTo(entity, viewer));
        assertEquals(1, spawns.get(), "tracked entity spawns displays");
        assertEquals(1, updates.get(), "tracked entity sends metadata and mount");
        assertTrue(tag.viewers.contains(viewerId));

        tracked.set(false);
        tag.syncViewer(entity, viewerId, tag.shouldBeVisibleTo(entity, viewer));
        assertEquals(1, despawns.get(), "untracked entity despawns displays");
        assertFalse(tag.viewers.contains(viewerId));
    }

    @Test
    void resolvesFreshEntityInstanceAfterChunkReloadWithoutRepublishing() {
        final World world = world("nordtal");
        final Entity stale = entity(world, 0, 64, 0, false);
        final Entity reloaded = entity(world, 0, 64, 0, true);
        final AtomicReference<Entity> liveRef = new AtomicReference<>(stale);
        final UUID viewerId = UUID.randomUUID();
        final Player viewer = player(viewerId, world, 5, 64, 0, true, false);
        final AtomicBoolean tracked = new AtomicBoolean(true);
        final AtomicInteger spawns = new AtomicInteger();
        final AtomicInteger updates = new AtomicInteger();
        final AtomicInteger despawns = new AtomicInteger();
        final EntityNameTag tag = testTag(liveRef, tracked, spawns, updates, despawns);

        // Unloaded chunk leaves stale entity invalid; no spawn happens.
        tag.syncViewer(stale, viewerId, tag.shouldBeVisibleTo(stale, viewer));
        assertEquals(0, spawns.get());

        // Chunk reloads with a fresh entity instance under the same UUID; tag resolves it live.
        liveRef.set(reloaded);
        assertSame(reloaded, tag.getEntity());
        tag.syncViewer(reloaded, viewerId, tag.shouldBeVisibleTo(reloaded, viewer));
        assertEquals(1, spawns.get());
        assertEquals(1, updates.get());
    }

    @Test
    void respawnForDropsAndRecreatesDisplaysWhenTracked() {
        final World world = world("nordtal");
        final Entity entity = entity(world, 0, 64, 0, true);
        final UUID viewerId = UUID.randomUUID();
        final Player viewer = player(viewerId, world, 5, 64, 0, true, false);
        final AtomicBoolean tracked = new AtomicBoolean(true);
        final AtomicInteger spawns = new AtomicInteger();
        final AtomicInteger updates = new AtomicInteger();
        final AtomicInteger despawns = new AtomicInteger();
        final EntityNameTag tag = testTag(new AtomicReference<>(entity), tracked, spawns, updates, despawns);

        tag.syncViewer(entity, viewerId, tag.shouldBeVisibleTo(entity, viewer));
        assertEquals(1, spawns.get());

        // Respawn or client world load forces a fresh spawn packet so the client receives displays again.
        tag.despawnFor(viewerId);
        tag.syncViewer(entity, viewerId, tag.shouldBeVisibleTo(entity, viewer));
        assertEquals(1, despawns.get());
        assertEquals(2, spawns.get());
        assertEquals(2, updates.get());
    }

    @Test
    void visibilityDistanceMatchesVanillaSixtyFourBlocks() {
        final World world = world("nordtal");
        final Entity entity = entity(world, 0, 64, 0, true);
        final AtomicBoolean tracked = new AtomicBoolean(true);
        final EntityNameTag tag = testTag(
                new AtomicReference<>(entity), tracked, new AtomicInteger(), new AtomicInteger(), new AtomicInteger());

        final Player within = player(UUID.randomUUID(), world, 63.9, 64, 0, true, false);
        final Player beyond = player(UUID.randomUUID(), world, 64.0, 64, 0, true, false);

        assertEquals(64, EntityNameTag.VISIBILITY_DISTANCE);
        assertTrue(tag.shouldBeVisibleTo(entity, within), "visible within 64 blocks");
        assertFalse(tag.shouldBeVisibleTo(entity, beyond), "hidden at 64 blocks and beyond");
    }

    @Test
    void liftedOffsetRaisesPlateAboveHeadScaledVertically() {
        final Vector lifted = EntityNameTag.liftedOffset(new Vector(0, 0.25, 0), new Vector(1, 1, 1));
        assertEquals(0.45, lifted.getY(), 1e-9);

        final Vector scaled = EntityNameTag.liftedOffset(new Vector(0, 0.25, 0), new Vector(1, 1.5, 1));
        assertEquals(0.55, scaled.getY(), 1e-9);
    }

    private static EntityNameTag testTag(
            final AtomicReference<Entity> liveRef,
            final AtomicBoolean tracked,
            final AtomicInteger spawns,
            final AtomicInteger updates,
            final AtomicInteger despawns) {
        return new EntityNameTag(
                UUID.randomUUID(),
                id -> liveRef.get(),
                id -> new PillHologram.Layers(Component.empty(), Component.empty()),
                (target, viewer) -> tracked.get(),
                null,
                null,
                null,
                null) {
            @Override
            void spawnDisplaysFor(final UUID viewerId) {
                spawns.incrementAndGet();
            }

            @Override
            void updateFor(final Entity live, final UUID viewerId) {
                updates.incrementAndGet();
            }

            @Override
            void despawnDisplaysFor(final UUID viewerId) {
                despawns.incrementAndGet();
            }
        };
    }

    private static World world(final String name) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (p, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> args[0] instanceof final World other && name.equals(other.getName());
                    case "hashCode" -> name.hashCode();
                    default -> null;
                });
    }

    private static Entity entity(
            final World world, final double x, final double y, final double z, final boolean valid) {
        final Location location = new Location(world, x, y, z);
        return (Entity) Proxy.newProxyInstance(
                Entity.class.getClassLoader(),
                new Class<?>[] {Entity.class},
                (p, method, args) -> switch (method.getName()) {
                    case "isValid" -> valid;
                    case "getWorld" -> world;
                    case "getLocation" -> location.clone();
                    default -> null;
                });
    }

    private static Player player(
            final UUID id,
            final World world,
            final double x,
            final double y,
            final double z,
            final boolean online,
            final boolean dead) {
        final Location location = new Location(world, x, y, z);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (p, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getWorld" -> world;
                    case "getLocation" -> location.clone();
                    case "isOnline" -> online;
                    case "isDead" -> dead;
                    default -> null;
                });
    }
}
