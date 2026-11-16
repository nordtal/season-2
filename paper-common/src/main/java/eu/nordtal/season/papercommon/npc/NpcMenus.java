package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.database.npc.Dialogue.Words;
import eu.nordtal.season.database.npc.NpcMenu;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.entity.Player;

/** The menus a dialogue on this server can hand a player to, each with the word its hint shows. */
public final class NpcMenus {

    private final Map<String, Menu> menus = new LinkedHashMap<>();

    /** A menu: its key in the dialogue, the hint word in a reader's language, and how it opens. */
    public record Menu(String key, Function<Locale, String> hint, Consumer<Player> open) {

        public Menu {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(hint, "hint");
            Objects.requireNonNull(open, "open");
        }
    }

    /** Offers the menu {@code key}. Register every menu before the first dialogue starts. */
    public NpcMenus register(final String key, final Function<Locale, String> hint, final Consumer<Player> open) {
        if (menus.putIfAbsent(key, new Menu(key, hint, open)) != null) {
            throw new IllegalArgumentException("the menu " + key + " is registered twice");
        }
        return this;
    }

    public Optional<Menu> menu(final String key) {
        return Optional.ofNullable(menus.get(key));
    }

    /** Returns the hint word of each menu in {@code locale}, by key. */
    public Map<String, String> hints(final Locale locale) {
        final Map<String, String> hints = new LinkedHashMap<>();
        menus.values().forEach(menu -> hints.put(menu.key(), menu.hint().apply(locale)));
        return Map.copyOf(hints);
    }

    /** Returns every registered menu with its English and German hint, for Steward's menu picker. */
    public List<NpcMenu> describe() {
        return menus.values().stream()
                .map(menu -> new NpcMenu(
                        menu.key(),
                        new Words(menu.hint().apply(Locale.ENGLISH), menu.hint().apply(Locale.GERMAN))))
                .toList();
    }
}
