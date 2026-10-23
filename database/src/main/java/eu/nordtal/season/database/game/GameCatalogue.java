package eu.nordtal.season.database.game;

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
 * What one Minecraft server knows of the game: the registries the pickers draw from, and their tags.
 *
 * @param minecraftVersion the version the server runs, such as {@code 26.2}
 * @param datapacks the names of the datapacks enabled at export
 * @param registries the entries of each registry by name, such as {@code item} or {@code advancement}
 * @param tags the tags of each registry by name
 * @param worlds the names of the worlds the server had loaded at export, in the server's order
 */
public record GameCatalogue(
        String minecraftVersion,
        List<String> datapacks,
        Map<String, List<Entry>> registries,
        Map<String, List<Tag>> tags,
        List<String> worlds) {

    /** The names of the registries a server exports. */
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
     * Merges the catalogues of servers of one version into every entry and tag any has, by id, the first kept.
     * Worlds belong to one server each, so the union has none.
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
        return new GameCatalogue(minecraftVersion, List.copyOf(datapacks), values(registries), values(tags), List.of());
    }

    private static <T> Map<String, List<T>> values(final Map<String, Map<String, T>> byRegistry) {
        final Map<String, List<T>> values = new LinkedHashMap<>();
        byRegistry.forEach((registry, byId) -> values.put(registry, new ArrayList<>(byId.values())));
        return values;
    }

    /**
     * One entry of a registry. The fields after {@code text} belong to the registry that has them.
     *
     * @param id the namespaced key a setting stores, such as {@code minecraft:oak_log}
     * @param key the translation key, none for an entry the game does not name, such as a sound
     * @param text the English text of the key, none without a key
     * @param parent the parent of an advancement, none for a root
     * @param description the English description of an advancement
     * @param frame the frame of an advancement: {@code task}, {@code goal} or {@code challenge}
     * @param icon the item an advancement shows
     * @param hidden whether an advancement stays hidden until it is made
     * @param subject the registry a statistic counts per entry of, such as {@code block}, none otherwise
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

        /** Creates an entry that has only a name. */
        public static Entry named(final String id, final @Nullable String key, final @Nullable String text) {
            return new Entry(id, key, text, null, null, null, null, null, null);
        }
    }

    /** One tag of a registry with its ids, nested tags resolved. */
    public record Tag(String id, List<String> values) {}
}
