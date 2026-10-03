package eu.nordtal.s2.papercommon.game;

import eu.nordtal.s2.messages.value.GameContent;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;

/**
 * Reads what a setting names in the game: a namespaced key such as {@code minecraft:oak_log}, as Steward writes it.
 *
 * A Bukkit name such as {@code OAK_LOG} reads as the same key, so a value typed by hand still resolves.
 */
public final class GameKeys {

    private static final String MINECRAFT = "minecraft:";

    private GameKeys() {}

    /** Returns {@code name} as a namespaced key in lower case, {@code minecraft} where it names no namespace. */
    public static String key(final String name) {
        final String trimmed = name.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() || trimmed.indexOf(':') >= 0 ? trimmed : MINECRAFT + trimmed;
    }

    /** Returns the material a setting names; empty for an unknown or a legacy one. */
    public static Optional<Material> material(final String name) {
        final String key = key(name);
        if (!key.startsWith(MINECRAFT)) {
            return Optional.empty();
        }
        final Material material =
                Material.getMaterial(key.substring(MINECRAFT.length()).toUpperCase(Locale.ROOT));
        return material == null || material.isLegacy() ? Optional.empty() : Optional.of(material);
    }

    /** Returns the item a setting names as game content, which the client names in its reader's language. */
    public static GameContent item(final String name) {
        return material(name)
                .map(material -> GameContent.of(material.translationKey()))
                .orElseGet(() -> new GameContent(key(name), key(name)));
    }

    /** Returns the statistic a setting names. */
    public static Optional<Statistic> statistic(final String name) {
        final String key = key(name);
        return Arrays.stream(Statistic.values())
                .filter(statistic -> statistic.getKey().toString().equals(key))
                .findFirst();
    }

    /** Returns the entity type a setting names; never {@code UNKNOWN}. */
    public static Optional<EntityType> entity(final String name) {
        final String key = key(name);
        return Arrays.stream(EntityType.values())
                .filter(type ->
                        type != EntityType.UNKNOWN && type.getKey().toString().equals(key))
                .findFirst();
    }
}
