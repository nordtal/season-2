package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.smp.region.Box;
import eu.nordtal.s2.smp.region.Boxes;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The balloon itself - the thing a player sees standing at the spawn, as opposed to the box they
 * step into.
 *
 * <h2>What it is</h2>
 * One {@link ItemDisplay} per configured balloon box, showing the pack's {@code nordtal:balloon}
 * item model, scaled to fill the box and centred in it. The model is the placeholder scaffold
 * from {@code resource-pack/tools/generate_dummy_textures.py} - two flat cuboids - until the real
 * Blockbench art replaces {@code assets/nordtal/models/item/balloon.json}; nothing here changes
 * when it does, because the entity only names the item model and the pack decides what that looks
 * like. That was the whole point of the item-model plumbing (docs/smp.md#the-nordtal-spawn), and
 * until 2026-09-05 nothing spawned the entity, so the plumbing existed and the balloon did not.
 *
 * <h2>Why an item display and not a block</h2>
 * A display entity is rendered by the client from the pack, has no hitbox, no collision, no AI, no
 * drops and no despawn timer, and is scaled with one transformation rather than built out of
 * blocks. The box a player walks into is a barrier-block floor and air; the display floats in it.
 *
 * <h2>Lifecycle</h2>
 * Spawned non-persistent and removed at disable, the way the spawn NPC is, so a restart never
 * leaves a second balloon behind - and any left by a crash are swept from the box at start.
 */
public final class BalloonDisplay {

    /** The pack's balloon item model - {@code resource-pack/src/assets/nordtal/items/balloon.json}. */
    public static final Key MODEL = Key.key("nordtal", "balloon");

    private final Plugin plugin;
    private final Boxes balloons;
    private final List<UUID> spawned = new ArrayList<>();

    public BalloonDisplay(final Plugin plugin, final Boxes balloons) {
        this.plugin = plugin;
        this.balloons = balloons;
    }

    /** Puts a balloon in every configured box, removing any this plugin left behind first. Main thread. */
    public void spawn() {
        remove();
        for (final Box box : balloons.all()) {
            final World world = Bukkit.getWorld(box.world());
            if (world == null) {
                plugin.getLogger().warning("the balloon's world '" + box.world()
                        + "' does not exist - no balloon was placed there");
                continue;
            }
            final Location centre = centre(world, box);
            sweep(world, centre, box);

            final ItemDisplay display = world.spawn(centre, ItemDisplay.class, entity -> {
                entity.setItemStack(item());
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setSilent(true);
                // The model is authored in a one-block cube centred on the entity; the box is
                // (max - min + 1) blocks on each axis, so the scale is the box's size.
                entity.setTransformation(new Transformation(
                        new Vector3f(),
                        new AxisAngle4f(),
                        new Vector3f(box.maxX() - box.minX() + 1,
                                box.maxY() - box.minY() + 1,
                                box.maxZ() - box.minZ() + 1),
                        new AxisAngle4f()));
            });
            spawned.add(display.getUniqueId());
        }
    }

    /** The item the display shows: anything at all, wearing the balloon model. */
    static ItemStack item() {
        final ItemStack stack = ItemStack.of(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        return stack;
    }

    /** The box's centre - inclusive corners, so the centre is half a block in from each edge's block. */
    static Location centre(final World world, final Box box) {
        return new Location(world,
                (box.minX() + box.maxX() + 1) / 2.0,
                (box.minY() + box.maxY() + 1) / 2.0,
                (box.minZ() + box.maxZ() + 1) / 2.0);
    }

    /** Removes any balloon display this plugin left in the box before the last restart. */
    private void sweep(final World world, final Location centre, final Box box) {
        final double reach = Math.max(box.maxX() - box.minX(), Math.max(box.maxY() - box.minY(),
                box.maxZ() - box.minZ())) + 1.0;
        world.getNearbyEntitiesByType(ItemDisplay.class, centre, reach).stream()
                .filter(display -> {
                    final ItemStack shown = display.getItemStack();
                    return MODEL.equals(shown.getData(DataComponentTypes.ITEM_MODEL));
                })
                .forEach(org.bukkit.entity.Entity::remove);
    }

    public void remove() {
        for (final UUID id : spawned) {
            final org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        spawned.clear();
    }
}
