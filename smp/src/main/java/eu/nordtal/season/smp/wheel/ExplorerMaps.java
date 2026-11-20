package eu.nordtal.season.smp.wheel;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.TagKey;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCursor;
import org.jspecify.annotations.Nullable;

/** Makes explorer maps for structure tags in the custom overworld. */
public final class ExplorerMaps {

    private static final int RADIUS = 50;
    private static final int MAP_EDGE = 20 * 512;

    private ExplorerMaps() {}

    public static boolean knowsTag(final String structure) {
        final NamespacedKey key = NamespacedKey.fromString(structure);
        if (key == null) {
            return false;
        }
        final var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
        final var tag = TagKey.create(RegistryKey.STRUCTURE, key);
        return registry.hasTag(tag) && !registry.getTagValues(tag).isEmpty();
    }

    static @Nullable ItemStack create(
            final World world, final Location handout, final String item, final int amount, final String structure) {
        final Material material = Material.matchMaterial(item);
        final NamespacedKey key = NamespacedKey.fromString(structure);
        if (material == null || key == null) {
            return null;
        }
        final MapCursor.Type decoration = switch (material) {
            case BURIED_MINESHAFT_MAP -> MapCursor.Type.MINESHAFT;
            case BURIED_ANCIENT_CITY_MAP -> MapCursor.Type.ANCIENT_CITY;
            default -> null;
        };
        final var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
        final var tag = TagKey.create(RegistryKey.STRUCTURE, key);
        if (decoration == null || !registry.hasTag(tag)) {
            return null;
        }
        final Location centre = world.getWorldBorder().getCenter();
        final Location origin = world.equals(handout.getWorld()) ? handout : centre;
        final Collection<Structure> structures = registry.getTagValues(tag);
        final Located found =
                insideCustomMap(origin, centre, start -> nearest(world, start, structures), Located::location);
        if (found == null) {
            return null;
        }
        final ItemStack explorer =
                Bukkit.createExplorerMap(world, found.origin(), found.structure(), decoration, RADIUS, false);
        if (explorer == null) {
            return null;
        }
        final var markers = explorer.getData(DataComponentTypes.MAP_DECORATIONS);
        if (markers == null
                || markers.decorations().isEmpty()
                || markers.decorations().values().stream().anyMatch(marker -> !inside(marker.x(), marker.z()))) {
            return null;
        }
        final ItemStack map = ItemStack.of(material, amount);
        map.copyDataFrom(
                explorer,
                type -> type.equals(DataComponentTypes.MAP_ID) || type.equals(DataComponentTypes.MAP_DECORATIONS));
        return map;
    }

    private record Located(Location origin, Structure structure, Location location) {}

    private static @Nullable Located nearest(
            final World world, final Location origin, final Collection<Structure> structures) {
        Located nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (final Structure structure : structures) {
            final var result = world.locateNearestStructure(origin, structure, RADIUS, false);
            if (result != null) {
                final Location location = Objects.requireNonNull(result.getLocation());
                final double dx = origin.getX() - location.getX();
                final double dz = origin.getZ() - location.getZ();
                final double squared = dx * dx + dz * dz;
                if (squared < distance) {
                    nearest = new Located(origin, structure, location);
                    distance = squared;
                }
            }
        }
        return nearest;
    }

    static <T> @Nullable T insideCustomMap(
            final Location origin,
            final Location centre,
            final Function<Location, @Nullable T> search,
            final Function<T, Location> location) {
        final T near = search.apply(origin);
        if (near != null
                && inside(location.apply(near).getX(), location.apply(near).getZ())) {
            return near;
        }
        final T fallback = origin.equals(centre) ? null : search.apply(centre);
        return fallback != null
                        && inside(
                                location.apply(fallback).getX(),
                                location.apply(fallback).getZ())
                ? fallback
                : null;
    }

    private static boolean inside(final double x, final double z) {
        return x >= 0 && x < MAP_EDGE && z >= 0 && z < MAP_EDGE;
    }
}
