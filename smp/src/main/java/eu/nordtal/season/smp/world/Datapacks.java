package eu.nordtal.season.smp.world;

import io.papermc.paper.datapack.Datapack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;

/**
 * Checks that the world-generation datapacks are installed and enabled, and stops the plugin when they are not.
 *
 * Datapacks are server-global and read once at start, so installing them is the container entrypoint's job.
 */
public final class Datapacks {

    private Datapacks() {}

    /** What the check found: the packs still missing, and what was actually enabled. */
    public record Result(List<String> missing, List<String> enabled) {

        public Result {
            missing = List.copyOf(missing);
            enabled = List.copyOf(enabled);
        }

        public boolean ok() {
            return missing.isEmpty();
        }

        /** One line for the log, naming what was wanted and what is there. */
        public String describe() {
            return "missing " + missing + "; enabled packs are " + enabled;
        }
    }

    /**
     * Matches each required name against the enabled packs, case-insensitively, as a substring.
     *
     * A substring, because Paper reports {@code file/<filename>} and the filename carries the version.
     */
    public static Result check(final List<String> required) {
        final List<String> enabled = new ArrayList<>();
        for (final Datapack pack : Bukkit.getDatapackManager().getEnabledPacks()) {
            enabled.add(pack.getName());
        }

        final List<String> missing = new ArrayList<>();
        for (final String want : required) {
            if (want == null || want.isBlank()) {
                continue;
            }
            final String needle = want.trim().toLowerCase(Locale.ROOT);
            final boolean found = enabled.stream()
                    .anyMatch(name -> name.toLowerCase(Locale.ROOT).contains(needle));
            if (!found) {
                missing.add(want.trim());
            }
        }
        return new Result(missing, enabled);
    }
}
