package eu.nordtal.s2.smp.world;

import io.papermc.paper.datapack.Datapack;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks that the world-generation datapacks are installed and enabled, and says so loudly when
 * they are not.
 *
 * <h2>Why this only checks</h2>
 * Measured on Paper 26.2 build 121 on 2026-09-01, because this design first assumed the opposite:
 *
 * <ul>
 *   <li><b>Datapacks are server-global.</b> A probe pack in {@code <level-name>/datapacks/} was
 *       listed and enabled; an identical probe in a secondary world's own {@code datapacks/} folder
 *       was never seen - not at start, not after that world was created, not after
 *       {@code refreshPacks()}.</li>
 *   <li><b>There is no per-world datapack API.</b> {@link io.papermc.paper.datapack.DatapackManager}
 *       hangs off {@code Server}, not {@code World}, and {@code WorldCreator} has no datapack
 *       option.</li>
 * </ul>
 *
 * <p>So every world this server generates - Nordtal, the farm world, the Nether and the End - gets
 * the same packs, and the farm world's nightly regeneration inherits them with nothing copied.
 * Installing them is the container entrypoint's job, before the server starts, because worldgen
 * registries are read once at start: a pack dropped in afterwards changes no terrain.
 *
 * <p>And terrain is never re-rolled once it is on disk. A farm world generated without Terralith is
 * one flat day; Nordtal generated without it is the whole season, on a world that has a spawn built
 * on it and therefore cannot be thrown away. That asymmetry is why a missing pack stops the plugin
 * instead of logging a warning nobody reads.
 */
public final class Datapacks {

    private Datapacks() {
    }

    /** What the check found: the packs still missing, and what was actually enabled. */
    public record Result(List<String> missing, List<String> enabled) {

        public Result {
            missing = List.copyOf(missing);
            enabled = List.copyOf(enabled);
        }

        public boolean ok() {
            return missing.isEmpty();
        }

        /** One line for the log, naming both halves - what was wanted and what is there. */
        public String describe() {
            return "missing " + missing + "; enabled packs are " + enabled;
        }
    }

    /**
     * Matches each required name against the enabled packs, case-insensitively, as a substring.
     *
     * <p>Substring rather than equality because Paper reports a zip as {@code file/<filename>} and
     * the filename carries the version - {@code file/Terralith_26.2_v2.6.4.zip}. Requiring the full
     * name would make every datapack update a config change in two places, and the version is
     * pinned by the entrypoint's checksum, which is the place that can actually enforce it.
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
