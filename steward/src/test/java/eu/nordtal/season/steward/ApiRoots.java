package eu.nordtal.season.steward;

import com.google.gson.reflect.TypeToken;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.steward.discord.DiscordApi;
import eu.nordtal.season.steward.live.LiveEvent;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** The roots that {@link ApiTypes} writes TypeScript types from: what steward's API answers and reads. */
final class ApiRoots {

    /** The records and enums that routes answer or read. {@link ApiTypes} reaches everything they hold. */
    static final List<Class<?>> ROOTS = Stream.of(
                    List.<Class<?>>of(LiveEvent.class, DiscordApi.Guild.class), StewardWire.ROOTS)
            .flatMap(List::stream)
            .toList();

    static final Map<String, Type> ALIASES = aliases();

    static final Map<Class<?>, String> NAMES = names();

    private ApiRoots() {}

    private static Map<Class<?>, String> names() {
        final Map<Class<?>, String> names = new LinkedHashMap<>();
        names.put(Alert.Level.class, "AlertLevel");
        names.put(DiscordApi.Guild.class, "GuildList");
        names.put(DiscordApi.Pick.class, "GuildEntry");
        names.putAll(StewardWire.NAMES);
        return Map.copyOf(names);
    }

    private static Map<String, Type> aliases() {
        final Map<String, Type> aliases = new LinkedHashMap<>();
        aliases.put("AlertPreferences", new TypeToken<Map<AlertType, Map<AlertChannel, Boolean>>>() {}.getType());
        aliases.put("MessageExamples", new TypeToken<Map<String, Map<String, String>>>() {}.getType());
        return aliases;
    }
}
