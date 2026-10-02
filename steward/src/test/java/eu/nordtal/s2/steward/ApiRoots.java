package eu.nordtal.s2.steward;

import com.google.gson.reflect.TypeToken;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertChannel;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.steward.api.ApiWire;
import eu.nordtal.s2.steward.live.LiveEvent;
import eu.nordtal.s2.steward.web.WebWire;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** What steward's API answers and reads, the roots {@link ApiTypes} writes TypeScript types from. */
final class ApiRoots {

    /** The records and enums a route answers or reads; whatever they hold is reached from them. */
    static final List<Class<?>> ROOTS = Stream.of(List.<Class<?>>of(LiveEvent.class), WebWire.ROOTS, ApiWire.ROOTS)
            .flatMap(List::stream)
            .toList();

    /** Maps a route builds without a record, under the name the frontend reads them by. */
    static final Map<String, Type> ALIASES = aliases();

    /** Types whose own simple name would say too little or collide. */
    static final Map<Class<?>, String> NAMES = names(Map.of(
            Alert.Level.class, "AlertLevel",
            ImageResult.State.class, "ImageState",
            AgentWire.Archive.class, "Backup",
            AgentWire.Plugin.class, "ServicePlugin",
            AgentWire.Plugins.class, "ServicePlugins"));

    private ApiRoots() {}

    private static Map<Class<?>, String> names(final Map<Class<?>, String> shared) {
        final Map<Class<?>, String> names = new LinkedHashMap<>(shared);
        names.putAll(WebWire.NAMES);
        return Map.copyOf(names);
    }

    private static Map<String, Type> aliases() {
        final Map<String, Type> aliases = new LinkedHashMap<>();
        aliases.put("AlertPreferences", new TypeToken<Map<AlertType, Map<AlertChannel, Boolean>>>() {}.getType());
        return aliases;
    }
}
