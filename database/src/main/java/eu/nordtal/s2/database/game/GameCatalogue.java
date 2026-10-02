package eu.nordtal.s2.database.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What one Minecraft server knows of the game: every registry the pickers draw from, and its tags.
 *
 * @param minecraftVersion the version the server runs, such as {@code 26.2}, which names the icons for it
 * @param datapacks the datapacks enabled when it was exported, by name
 * @param registries the entries of each registry, by its name, such as {@code item} or {@code advancement}
 * @param tags the tags of each registry, by the same name
 */
public record GameCatalogue(
        String minecraftVersion,
        List<String> datapacks,
        Map<String, List<Entry>> registries,
        Map<String, List<Tag>> tags) {

    /** The registries a server exports, by the name its entries are filed under. */
    public static final List<String> REGISTRIES = List.of(
            "item",
            "block",
            "entity_type",
            "advancement",
            "statistic",
            "enchantment",
            "biome",
            "mob_effect",
            "sound_event",
            "damage_type");

    /**
     * What several servers of one version know together: every entry and tag any of them has, by id, the first kept.
     *
     * Servers of one version share the vanilla registries; a datapack on one adds what only it has.
     */
    public static GameCatalogue union(final String minecraftVersion, final Collection<GameCatalogue> catalogues) {
        final Set<String> datapacks = new TreeSet<>();
        final Map<String, Map<String, Entry>> registries = new TreeMap<>();
        final Map<String, Map<String, Tag>> tags = new TreeMap<>();
        for (final GameCatalogue catalogue : catalogues) {
            if (!catalogue.minecraftVersion().equals(minecraftVersion)) {
                continue;
            }
            datapacks.addAll(catalogue.datapacks());
            catalogue.registries().forEach((registry, entries) -> {
                final Map<String, Entry> byId = registries.computeIfAbsent(registry, _ -> new TreeMap<>());
                entries.forEach(entry -> byId.putIfAbsent(entry.id(), entry));
            });
            catalogue.tags().forEach((registry, declared) -> {
                final Map<String, Tag> byId = tags.computeIfAbsent(registry, _ -> new TreeMap<>());
                declared.forEach(tag -> byId.putIfAbsent(tag.id(), tag));
            });
        }
        return new GameCatalogue(minecraftVersion, List.copyOf(datapacks), values(registries), values(tags));
    }

    private static <T> Map<String, List<T>> values(final Map<String, Map<String, T>> byRegistry) {
        final Map<String, List<T>> values = new LinkedHashMap<>();
        byRegistry.forEach((registry, byId) -> values.put(registry, new ArrayList<>(byId.values())));
        return values;
    }

    /**
     * One entry of a registry; the fields after {@code text} belong to the one registry that has them.
     *
     * @param id the namespaced key a setting stores, such as {@code minecraft:oak_log}
     * @param key the translation key, or none for an entry the game names nowhere, such as a sound
     * @param text the English the server renders the key to, or none without a key
     * @param parent an advancement's parent, none for a root
     * @param description the English of an advancement's description, as its tooltip shows it
     * @param frame an advancement's frame: {@code task}, {@code goal} or {@code challenge}
     * @param icon the item an advancement shows
     * @param hidden whether an advancement stays hidden until it is made
     * @param subject the registry a statistic counts per entry of, such as {@code block}, none for one it does not
     */
    public record Entry(
            String id,
            @Nullable String key,
            @Nullable String text,
            @Nullable String parent,
            @Nullable String description,
            @Nullable String frame,
            @Nullable String icon,
            @Nullable Boolean hidden,
            @Nullable String subject) {

        /** An entry with a name and nothing of its registry's own. */
        public static Entry named(final String id, final @Nullable String key, final @Nullable String text) {
            return new Entry(id, key, text, null, null, null, null, null, null);
        }
    }

    /** One tag of a registry and the ids in it, with nested tags already resolved. */
    public record Tag(String id, List<String> values) {}
}
