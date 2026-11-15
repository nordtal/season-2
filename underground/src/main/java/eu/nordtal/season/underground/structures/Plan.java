package eu.nordtal.season.underground.structures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every change the tool makes to a world, decided before any file is written. */
public final class Plan {

    /** A structure start to write into its start chunk, with pieces that each lie within reach of it. */
    public record Start(String structure, int chunkX, int chunkZ, String piece, List<Box> pieces) {}

    /** A spawner to take out, which leaves air, or water where water touches it. */
    public record Removal(Finds.Spawner spawner, String reason) {}

    /** An empty spawner that is given a mob. */
    public record Retype(Finds.Spawner spawner, String mob) {}

    /** A hive without bees that is given some. */
    public record Bees(Finds.Hive hive, int count) {}

    /** A villager to place, by its feet, with the villager type of its biome. */
    public record Villager(double x, double y, double z, String type) {}

    final Map<String, Start> starts = new LinkedHashMap<>();
    final List<Removal> removals = new ArrayList<>();
    final List<Retype> retypes = new ArrayList<>();
    final List<Bees> bees = new ArrayList<>();
    final List<Villager> villagers = new ArrayList<>();
    final List<String> report = new ArrayList<>();

    /** Returns the starts to write. */
    public List<Start> starts() {
        return List.copyOf(starts.values());
    }

    /** Returns the start of a structure in a chunk, creating it when the plan has none there yet. */
    Start start(final String structure, final int chunkX, final int chunkZ, final String piece) {
        return starts.computeIfAbsent(
                structure + " " + chunkX + " " + chunkZ,
                key -> new Start(structure, chunkX, chunkZ, piece, new ArrayList<>()));
    }

    /** Returns the spawners to take out. */
    public List<Removal> removals() {
        return List.copyOf(removals);
    }

    /** Returns the spawners that are given a mob. */
    public List<Retype> retypes() {
        return List.copyOf(retypes);
    }

    /** Returns the hives that are given bees. */
    public List<Bees> bees() {
        return List.copyOf(bees);
    }

    /** Returns the villagers to place. */
    public List<Villager> villagers() {
        return List.copyOf(villagers);
    }

    /** Returns one line per place and decision, tab separated. */
    public List<String> report() {
        return List.copyOf(report);
    }
}
