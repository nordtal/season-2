package eu.nordtal.season.underground.structures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The structures command: scans a world, plans the map's real structures and writes the files it changes. */
public final class Structures {

    /** The name of the report the command writes beside the changed files. */
    public static final String REPORT = "structures-report.tsv";

    private Structures() {}

    /** Runs the command on a world and writes every region and entity file it changes, with the report, below out. */
    public static String run(
            final Path world, final Path pack, final long seed, final Path out, final List<int[]> regions)
            throws IOException {
        final long began = System.nanoTime();
        final Finds finds = Scan.run(world, regions);
        final int version = version(finds);
        final long scanned = System.nanoTime();
        final Map<String, Reach> reaches = new LinkedHashMap<>();
        for (final String set : List.of(Planner.VILLAGES, Planner.OUTPOSTS, Planner.MONUMENTS, Planner.STRONGHOLDS)) {
            reaches.put(
                    set,
                    new Reach(
                            Spread.read(pack.resolve("data/nordtal/worldgen/structure_set/" + set + ".json")),
                            seed,
                            finds));
        }
        final Plan plan = new Planner(finds, new Area(world), reaches, seed).plan();
        final long planned = System.nanoTime();
        final List<String> lines = new ArrayList<>();
        lines.add("kind\tbox\tdecision\tdetail");
        lines.addAll(plan.report());
        Files.createDirectories(out);
        Files.write(out.resolve(REPORT), lines, StandardCharsets.UTF_8);
        final Edits.Summary summary = new Edits(world, out, finds, version, seed).write(plan);
        return "structures finished: " + summary + " chunks=" + finds.fullChunks.size() + " scan="
                + seconds(began, scanned) + "s plan=" + seconds(scanned, planned) + "s write="
                + seconds(planned, System.nanoTime()) + "s";
    }

    /** Returns the newest data version of the world's chunks, which every chunk the tool changes must have. */
    static int version(final Finds finds) {
        if (finds.versions.isEmpty()) {
            throw new IllegalStateException("The world has no chunks.");
        }
        return finds.versions.lastKey();
    }

    private static long seconds(final long from, final long to) {
        return (to - from) / 1_000_000_000L;
    }
}
