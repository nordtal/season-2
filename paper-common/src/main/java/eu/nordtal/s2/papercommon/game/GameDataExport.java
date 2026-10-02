package eu.nordtal.s2.papercommon.game;

import eu.nordtal.s2.database.game.GameCatalogue;
import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.text.PaperComponents;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Keyed;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.damage.DamageType;
import org.jspecify.annotations.Nullable;

/**
 * Reads what this server knows of the game into one {@link GameCatalogue}: every registry the pickers offer.
 *
 * English is the server's own en_us; recipe advancements, thousands that nobody picks, are left out.
 */
public final class GameDataExport {

    /** What a death message's player and killer are rendered as, so the cause reads as a sentence. */
    private static final List<Component> DEATH_ARGUMENTS =
            List.of(Component.text("Someone"), Component.text("something"));

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.builder()
            .flattener(PaperComponents.flattener())
            .build();

    private GameDataExport() {}

    /** Reads every registry; on the main thread, once the server has finished loading its datapacks. */
    public static GameCatalogue read(final Server server, final Consumer<String> warn) {
        final Map<String, List<GameCatalogue.Entry>> registries = new LinkedHashMap<>();
        final Map<String, List<GameCatalogue.Tag>> tags = new LinkedHashMap<>();
        final RegistryAccess access = RegistryAccess.registryAccess();
        named("item", access.getRegistry(RegistryKey.ITEM), registries, tags, warn);
        named("block", access.getRegistry(RegistryKey.BLOCK), registries, tags, warn);
        named("entity_type", access.getRegistry(RegistryKey.ENTITY_TYPE), registries, tags, warn);
        named("enchantment", access.getRegistry(RegistryKey.ENCHANTMENT), registries, tags, warn);
        named("biome", access.getRegistry(RegistryKey.BIOME), registries, tags, warn);
        named("mob_effect", access.getRegistry(RegistryKey.MOB_EFFECT), registries, tags, warn);
        named("sound_event", access.getRegistry(RegistryKey.SOUND_EVENT), registries, tags, warn);
        registries.put("damage_type", damageTypes(access.getRegistry(RegistryKey.DAMAGE_TYPE)));
        tags.put("damage_type", tagsOf(access.getRegistry(RegistryKey.DAMAGE_TYPE), warn));
        registries.put("statistic", statistics(warn));
        registries.put("advancement", advancements(server.advancementIterator()));
        final List<String> datapacks = server.getDatapackManager().getEnabledPacks().stream()
                .map(Datapack::getName)
                .sorted()
                .toList();
        return new GameCatalogue(server.getMinecraftVersion(), datapacks, registries, tags);
    }

    private static <T extends Keyed> void named(
            final String name,
            final Registry<T> registry,
            final Map<String, List<GameCatalogue.Entry>> registries,
            final Map<String, List<GameCatalogue.Tag>> tags,
            final Consumer<String> warn) {
        final List<GameCatalogue.Entry> entries = new ArrayList<>();
        registry.stream().forEach(value -> {
            final String key = keyOf(value);
            entries.add(GameCatalogue.Entry.named(value.getKey().toString(), key, textOf(key, List.of())));
        });
        entries.sort(Comparator.comparing(GameCatalogue.Entry::id));
        registries.put(name, entries);
        tags.put(name, tagsOf(registry, warn));
    }

    private static List<GameCatalogue.Entry> damageTypes(final Registry<DamageType> registry) {
        return registry.stream()
                .map(type -> {
                    // A damage type's key is its death message's suffix; the message is what names it.
                    final String key = "death.attack." + type.getTranslationKey();
                    return GameCatalogue.Entry.named(type.getKey().toString(), key, textOf(key, DEATH_ARGUMENTS));
                })
                .sorted(Comparator.comparing(GameCatalogue.Entry::id))
                .toList();
    }

    private static <T extends Keyed> List<GameCatalogue.Tag> tagsOf(
            final Registry<T> registry, final Consumer<String> warn) {
        final List<GameCatalogue.Tag> found = new ArrayList<>();
        try {
            for (final Tag<T> tag : registry.getTags()) {
                found.add(new GameCatalogue.Tag(
                        tag.tagKey().key().asString(),
                        tag.values().stream()
                                .map(value -> value.key().asString())
                                .sorted()
                                .toList()));
            }
        } catch (final UnsupportedOperationException noTags) {
            warn.accept("a registry offers no tags: " + noTags.getMessage());
        }
        found.sort(Comparator.comparing(GameCatalogue.Tag::id));
        return found;
    }

    /** Every statistic by the key a setting names it with, with its vanilla translation key where it is known. */
    private static List<GameCatalogue.Entry> statistics(final Consumer<String> warn) {
        final Map<Statistic, String> vanilla = vanillaStatistics(warn);
        final List<GameCatalogue.Entry> entries = new ArrayList<>();
        for (final Statistic statistic : Registry.STATISTIC) {
            final String subject = switch (statistic.getType()) {
                case UNTYPED -> null;
                case ITEM -> "item";
                case BLOCK -> "block";
                case ENTITY -> "entity_type";
            };
            final String id = vanilla.get(statistic);
            final String key = id == null ? null : (subject == null ? "stat." : "stat_type.") + id.replace(':', '.');
            entries.add(new GameCatalogue.Entry(
                    statistic.getKey().toString(),
                    key,
                    statisticText(statistic, key),
                    null,
                    null,
                    null,
                    null,
                    subject));
        }
        entries.sort(Comparator.comparing(GameCatalogue.Entry::id));
        return entries;
    }

    /**
     * Bukkit's statistics by the vanilla id the server's language names them with, such as {@code minecraft:mined}.
     *
     * The API has no translation key for a statistic, so the server's own mapping is read; without it, none.
     */
    private static Map<Statistic, String> vanillaStatistics(final Consumer<String> warn) {
        final Map<Statistic, String> ids = new HashMap<>();
        try {
            final Class<?> type = Class.forName("org.bukkit.craftbukkit.CraftStatistic");
            final Field key = type.getDeclaredField("key");
            final Field bukkit = type.getDeclaredField("bukkit");
            key.setAccessible(true);
            bukkit.setAccessible(true);
            for (final Object constant : type.getEnumConstants()) {
                if (bukkit.get(constant) instanceof Statistic statistic && key.get(constant) != null) {
                    ids.put(statistic, String.valueOf(key.get(constant)));
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException changed) {
            warn.accept(
                    "statistics are exported without names: the server's mapping could not be read (" + changed + ")");
        }
        return ids;
    }

    /** Every advancement that is not a recipe's, with what its tree and its card need. */
    private static List<GameCatalogue.Entry> advancements(final Iterator<Advancement> all) {
        final List<GameCatalogue.Entry> entries = new ArrayList<>();
        all.forEachRemaining(advancement -> {
            if (advancement.getKey().getKey().startsWith("recipes/")) {
                return;
            }
            final AdvancementDisplay display = advancement.getDisplay();
            final Advancement parent = advancement.getParent();
            final String parentId = parent == null ? null : parent.getKey().toString();
            if (display == null) {
                entries.add(new GameCatalogue.Entry(
                        advancement.getKey().toString(), null, null, parentId, null, null, null, null));
                return;
            }
            final Component title = display.title();
            entries.add(new GameCatalogue.Entry(
                    advancement.getKey().toString(),
                    title instanceof TranslatableComponent translatable ? translatable.key() : null,
                    PLAIN.serialize(title),
                    parentId,
                    display.frame().name().toLowerCase(Locale.ROOT),
                    display.icon().getType().getKey().toString(),
                    display.isHidden(),
                    null));
        });
        entries.sort(Comparator.comparing(GameCatalogue.Entry::id));
        return entries;
    }

    private static @Nullable String keyOf(final Object value) {
        if (value instanceof net.kyori.adventure.translation.Translatable translatable) {
            return translatable.translationKey();
        }
        return null;
    }

    /**
     * A statistic's English: the game's own where it is a name, such as {@code Times Mined}.
     *
     * The game words two as sentences with the mob in them ("You killed %s %s"); those are named after the key.
     */
    private static @Nullable String statisticText(final Statistic statistic, final @Nullable String key) {
        final String text = textOf(key, List.of());
        if (text == null || text.equals(textOf(key, List.of(Component.text("1"), Component.text("2"))))) {
            return text;
        }
        final String words = statistic.getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** The English the server's language gives {@code key}, or none when it has no text for it. */
    private static @Nullable String textOf(final @Nullable String key, final List<Component> arguments) {
        if (key == null) {
            return null;
        }
        final String text = PLAIN.serialize(Component.translatable(key, arguments));
        return text.isBlank() || text.equals(key) ? null : text;
    }
}
