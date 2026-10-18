package eu.nordtal.season.smp.travel;

import eu.nordtal.season.smp.region.Box;
import eu.nordtal.season.smp.region.Boxes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/**
 * Shows one {@link ItemDisplay} per balloon box, scaled to fill the box.
 * {@link #remove()} deletes them at disable, and {@link #spawn()} deletes leftovers first.
 */
public final class BalloonDisplay {

    /** The item model {@code resource-pack/src/assets/nordtal/items/balloon.json}. */
    public static final Key MODEL = Key.key("nordtal", "balloon");

    private final Plugin plugin;
    private final Boxes balloons;
    private final List<UUID> spawned = new ArrayList<>();

    public BalloonDisplay(final Plugin plugin, final Boxes balloons) {
        this.plugin = plugin;
        this.balloons = balloons;
    }

    /**
     * Creates a balloon in every configured box. Main thread only.
     * It first removes the balloons of this plugin that remain.
     */
    public void spawn() {
        remove();
        for (final Box box : balloons.all()) {
            final World world = Bukkit.getWorld(box.world());
            if (world == null) {
                plugin.getLogger()
                        .warning("the balloon's world '" + box.world()
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
                entity.setTransformation(new Transformation(
                        new Vector3f(),
                        new AxisAngle4f(),
                        new Vector3f(
                                box.maxX() - box.minX() + 1, box.maxY() - box.minY() + 1, box.maxZ() - box.minZ() + 1),
                        new AxisAngle4f()));
            });
            spawned.add(display.getUniqueId());
        }
    }

    static ItemStack item() {
        final ItemStack stack = ItemStack.of(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        return stack;
    }

    /** Returns the centre of {@code box}, counting each corner block as a whole block. */
    static Location centre(final World world, final Box box) {
        return new Location(
                world,
                (box.minX() + box.maxX() + 1) / 2.0,
                (box.minY() + box.maxY() + 1) / 2.0,
                (box.minZ() + box.maxZ() + 1) / 2.0);
    }

    private void sweep(final World world, final Location centre, final Box box) {
        final double reach =
                Math.max(box.maxX() - box.minX(), Math.max(box.maxY() - box.minY(), box.maxZ() - box.minZ())) + 1.0;
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
