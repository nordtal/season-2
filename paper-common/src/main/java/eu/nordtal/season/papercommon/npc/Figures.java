package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.database.npc.NpcSkin;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerTextures;
import org.jspecify.annotations.Nullable;

/**
 * The figure of every NPC, a {@link Mannequin} that stands where an admin placed it and turns its head to players.
 * A changed NPC gets a new figure; a stray figure goes when its chunk loads.
 * Call it on the main thread only.
 */
public final class Figures implements Listener {

    static final double SWEEP = 4.0;
    static final double EYE = 1.62;
    static final double FOLLOW = 6.0;
    /** How far in front of the admin a preview's figure stands, in blocks. */
    static final double PREVIEW_AHEAD = 3.0;

    private final Plugin plugin;
    private final NamePlates plates;
    private final NamespacedKey tag;
    private final Map<UUID, Standing> standing = new HashMap<>();
    private final Map<UUID, UUID> owners = new HashMap<>();

    private record Standing(Npc npc, UUID figure, HeadTurn head) {}

    public Figures(final Plugin plugin, final NamePlates plates) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.plates = Objects.requireNonNull(plates, "plates");
        this.tag = new NamespacedKey(plugin, "npc");
    }

    /** Places the figures of {@code npcs}, and returns the ids of the NPCs whose figure changed or went. */
    public Set<UUID> apply(final List<Npc> npcs) {
        final Map<UUID, Npc> wanted = new HashMap<>();
        npcs.forEach(npc -> wanted.put(npc.id(), npc));
        final List<UUID> changed = new ArrayList<>();
        for (final Standing old : List.copyOf(standing.values())) {
            if (!old.npc().equals(wanted.get(old.npc().id()))) {
                changed.add(old.npc().id());
                remove(old);
            }
        }
        for (final Npc npc : npcs) {
            if (!standing.containsKey(npc.id())) {
                spawn(npc);
            }
        }
        return Set.copyOf(changed);
    }

    /** Returns the NPC whose figure {@code entity} is. */
    public Optional<Npc> npcOf(final Entity entity) {
        final UUID npc = owners.get(entity.getUniqueId());
        final Standing at = npc == null ? null : standing.get(npc);
        return at == null ? Optional.empty() : Optional.of(at.npc());
    }

    /** Returns whether {@code entity} is the figure of an NPC, a current one or a stray one with the tag. */
    public boolean is(final Entity entity) {
        return owners.containsKey(entity.getUniqueId())
                || entity.getPersistentDataContainer().has(tag, PersistentDataType.STRING);
    }

    /** Returns the figure of the NPC {@code npc} while its chunk is loaded. */
    public @Nullable Mannequin figure(final UUID npc) {
        final Standing at = standing.get(npc);
        return at == null ? null : Bukkit.getEntity(at.figure()) instanceof final Mannequin figure ? figure : null;
    }

    /** Turns each head one tick towards its talker, else the nearest player close by. */
    public void turnHeads(final Function<UUID, @Nullable Player> talker) {
        for (final Standing at : standing.values()) {
            if (Bukkit.getEntity(at.figure()) instanceof final Mannequin figure) {
                final HeadTurn.Pose pose =
                        at.head().tick(toward(figure, talker.apply(at.npc().id())));
                figure.setRotation(pose.headYaw(), pose.headPitch());
                figure.setBodyYaw(pose.bodyYaw());
            }
        }
    }

    /** Removes every figure, as the plugin stops. */
    public void removeAll() {
        List.copyOf(standing.values()).forEach(this::remove);
    }

    /** Removes a stray figure as its entities load: a tagged one this server does not place, or any near an NPC. */
    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        for (final Entity entity : event.getEntities()) {
            if (entity instanceof Mannequin
                    && !owners.containsKey(entity.getUniqueId())
                    && (entity.getPersistentDataContainer().has(tag, PersistentDataType.STRING)
                            || nearAnNpc(entity.getLocation()))) {
                entity.remove();
            }
        }
    }

    // A chunk's entities load after the chunk, so the sweep at spawn can miss an untagged figure of an older build.
    private boolean nearAnNpc(final Location at) {
        for (final Standing placed : standing.values()) {
            final Npc.Place place = placed.npc().place();
            if (at.getWorld() != null
                    && at.getWorld().getName().equals(place.world())
                    && at.distanceSquared(new Location(at.getWorld(), place.x(), place.y(), place.z()))
                            <= SWEEP * SWEEP) {
                return true;
            }
        }
        return false;
    }

    private void spawn(final Npc npc) {
        final Npc.Place place = npc.place();
        final World world = Bukkit.getWorld(place.world());
        if (world == null) {
            plugin.getLogger()
                    .warning("npc world missing, figure not placed: npc=" + npc.id() + " world=" + place.world());
            return;
        }
        final Location at = new Location(world, place.x(), place.y(), place.z(), place.yaw(), 0f);
        // getNearbyEntitiesByType searches loaded chunks only.
        at.getChunk().load();
        world.getNearbyEntitiesByType(Mannequin.class, at, SWEEP).stream()
                .filter(stray -> !owners.containsKey(stray.getUniqueId()))
                .forEach(Entity::remove);
        final Mannequin figure = spawn(at, npc, false);
        standing.put(npc.id(), new Standing(npc, figure.getUniqueId(), new HeadTurn(place.yaw())));
        owners.put(figure.getUniqueId(), npc.id());
        plates.attach(figure, npc);
    }

    private void dress(final Mannequin mannequin, final Npc npc) {
        mannequin.setImmovable(true);
        mannequin.setInvulnerable(true);
        mannequin.setSilent(true);
        // Paper discards a non-persistent entity on chunk unload.
        mannequin.setPersistent(true);
        // Removes the default description, the text "NPC".
        mannequin.setDescription(null);
        mannequin
                .getPersistentDataContainer()
                .set(tag, PersistentDataType.STRING, npc.id().toString());
        mannequin.setPose(pose(npc.pose()), true);
        if (npc.skin() != null) {
            // The pack holds the texture, so the figure needs no signed profile.
            mannequin.setProfile(ResolvableProfile.resolvableProfile()
                    .skinPatch(patch -> patch.body(NpcLooks.body(npc))
                            .model(
                                    npc.skinModel() == NpcSkin.SkinModel.SLIM
                                            ? PlayerTextures.SkinModel.SLIM
                                            : PlayerTextures.SkinModel.CLASSIC))
                    .build());
        }
        npc.equipment().forEach((slot, item) -> wear(mannequin, npc, slot, item));
        mannequin.setRotation(npc.place().yaw(), 0f);
        mannequin.setBodyYaw(npc.place().yaw());
    }

    private void wear(final Mannequin mannequin, final Npc npc, final Npc.Slot slot, final Npc.Equipment item) {
        try {
            final ItemStack stack =
                    ItemStack.deserializeBytes(Base64.getDecoder().decode(item.data()));
            Objects.requireNonNull(mannequin.getEquipment(), "equipment").setItem(slot(slot), stack);
        } catch (final RuntimeException failure) {
            plugin.getLogger()
                    .warning("npc item not worn: npc=" + npc.id() + " slot=" + slot + " item=" + item.item() + " cause="
                            + failure.getMessage());
        }
    }

    /** Places a private figure of {@code npc} in front of {@code viewer}, for an admin's unsaved preview. */
    Mannequin spawnPreview(final Npc npc, final Player viewer) {
        final Location from = Objects.requireNonNull(viewer.getLocation(), "location");
        final double yaw = Math.toRadians(from.getYaw());
        // Facing the admin: a figure's yaw is the admin's, turned half round.
        final Location at = new Location(
                viewer.getWorld(),
                from.getX() - Math.sin(yaw) * PREVIEW_AHEAD,
                from.getY(),
                from.getZ() + Math.cos(yaw) * PREVIEW_AHEAD,
                from.getYaw() + 180f,
                0f);
        final Mannequin figure = spawn(at, npc, true);
        viewer.showEntity(plugin, figure);
        return figure;
    }

    /** The one way to a figure; a preview's is seen by nobody until shown, faces {@code at} and goes with its chunk. */
    private Mannequin spawn(final Location at, final Npc npc, final boolean preview) {
        return Objects.requireNonNull(at.getWorld(), "world").spawn(at, Mannequin.class, mannequin -> {
            if (preview) {
                mannequin.setVisibleByDefault(false);
            }
            dress(mannequin, npc);
            if (preview) {
                mannequin.setPersistent(false);
                mannequin.setRotation(at.getYaw(), 0f);
                mannequin.setBodyYaw(at.getYaw());
            }
        });
    }

    /** Removes a figure placed for a preview. */
    void removePreview(final Mannequin figure) {
        figure.remove();
    }

    private void remove(final Standing at) {
        standing.remove(at.npc().id());
        owners.remove(at.figure());
        final Entity entity = Bukkit.getEntity(at.figure());
        if (entity instanceof final Mannequin figure) {
            plates.detach(figure);
        }
        if (entity != null) {
            entity.remove();
        }
    }

    private static HeadTurn.@Nullable Offset toward(final Mannequin figure, final @Nullable Player talker) {
        final Location eye = figure.getLocation().add(0, EYE, 0);
        Player seen = talker != null && talker.getWorld().equals(figure.getWorld()) ? talker : null;
        if (seen == null) {
            double nearest = FOLLOW * FOLLOW;
            for (final Player player : figure.getWorld().getNearbyPlayers(eye, FOLLOW)) {
                final double distance = player.getEyeLocation().distanceSquared(eye);
                if (distance <= nearest) {
                    nearest = distance;
                    seen = player;
                }
            }
        }
        if (seen == null) {
            return null;
        }
        final Location to = seen.getEyeLocation();
        return new HeadTurn.Offset(to.getX() - eye.getX(), to.getY() - eye.getY(), to.getZ() - eye.getZ());
    }

    static Pose pose(final Npc.Pose pose) {
        return switch (pose) {
            case STAND -> Pose.STANDING;
            case CROUCH -> Pose.SNEAKING;
            case SIT -> Mannequin.validPoses().contains(Pose.SITTING) ? Pose.SITTING : Pose.STANDING;
        };
    }

    static EquipmentSlot slot(final Npc.Slot slot) {
        return switch (slot) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
            case HAND -> EquipmentSlot.HAND;
            case OFF_HAND -> EquipmentSlot.OFF_HAND;
        };
    }
}
