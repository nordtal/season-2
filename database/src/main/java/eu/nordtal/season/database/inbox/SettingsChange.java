package eu.nordtal.season.database.inbox;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A change to one group of a server's settings that Steward has not saved yet, for the server to try.
 *
 * @param values the JSON text of each changed path
 * @param resets the paths that go back to their default
 */
public record SettingsChange(String group, Map<String, String> values, List<String> resets) {

    public SettingsChange {
        Objects.requireNonNull(group, "group");
        values = Map.copyOf(values);
        resets = List.copyOf(resets);
    }
}
