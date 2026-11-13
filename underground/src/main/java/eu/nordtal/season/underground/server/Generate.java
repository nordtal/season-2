package eu.nordtal.season.underground.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Generates the world the underground comes from: rock to the top with the game's caves, and only cave structures. */
public final class Generate {

    private static final String TAGS = "data/minecraft/tags/worldgen/biome/has_structure/";
    private static final Set<String> KEPT = Set.of("mineshaft", "mineshaft_mesa", "trial_chambers", "ancient_city");
    private static final Pattern MARKED = Pattern.compile("Marked|No chunks were marked");
    private static final Pattern UNMARKED = Pattern.compile("Unmarked|No chunks were unmarked|All chunks");
    private static final Pattern SAVED = Pattern.compile("Saved the game");
    private static final Pattern DATA_MAJOR = Pattern.compile("\"data_major\"\\s*:\\s*(\\d+)");
    private static final int BATCH = 16;

    private Generate() {}

    /** Creates the world with its data pack and generates every chunk of the regions. */
    public static void run(final ScratchServer.Setup setup, final Path data, final List<int[]> regions)
            throws IOException, InterruptedException {
        ScratchServer.prepare(setup);
        dataPack(data, setup.directory().resolve("world/datapacks/underground"));
        try (ScratchServer server = ScratchServer.start(setup)) {
            load(server, regions);
            server.ask("save-all flush", SAVED, Duration.ofMinutes(30));
        }
    }

    /** Loads every chunk of the regions once, in batches the server takes in one command, and lets them go again. */
    static void load(final ScratchServer server, final List<int[]> regions) throws IOException, InterruptedException {
        int done = 0;
        for (final int[] region : regions) {
            for (int x = 0; x < 32; x += BATCH) {
                for (int z = 0; z < 32; z += BATCH) {
                    final int blockX = (region[0] * 32 + x) * 16;
                    final int blockZ = (region[1] * 32 + z) * 16;
                    final String range =
                            blockX + " " + blockZ + " " + (blockX + BATCH * 16 - 1) + " " + (blockZ + BATCH * 16 - 1);
                    server.ask("forceload add " + range, MARKED, Duration.ofMinutes(30));
                    server.ask("forceload remove " + range, UNMARKED, Duration.ofMinutes(5));
                }
            }
            done++;
            System.out.println("region generated: x=" + region[0] + " z=" + region[1] + " done=" + done + " regions="
                    + regions.size());
        }
    }

    /** Writes the data pack: the terrain solid up to the top, and every structure off but those of the caves. */
    static void dataPack(final Path data, final Path pack) throws IOException {
        final Path functions = pack.resolve("data/minecraft/worldgen/density_function/overworld");
        Files.createDirectories(functions);
        Files.writeString(functions.resolve("sloped_cheese.json"), "10.0\n");
        Files.writeString(functions.resolve("preliminary_surface_level.json"), "245.0\n");
        try (ZipFile jar = new ZipFile(data.toFile())) {
            final ZipEntry version = jar.getEntry("version.json");
            if (version == null) {
                throw new IOException(data + " holds no version.json; name the jar that holds the game's data.");
            }
            final String format;
            try (InputStream in = jar.getInputStream(version)) {
                final Matcher major = DATA_MAJOR.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!major.find()) {
                    throw new IOException("The version.json of " + data + " names no data pack format.");
                }
                format = major.group(1);
            }
            Files.writeString(
                    pack.resolve("pack.mcmeta"),
                    "{\"pack\": {\"description\": \"Rock with the caves of the game\", \"min_format\": " + format
                            + ", \"max_format\": " + format + "}}\n");
            final Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final String name = entries.nextElement().getName();
                if (!name.startsWith(TAGS) || !name.endsWith(".json")) {
                    continue;
                }
                final String structure = name.substring(TAGS.length(), name.length() - ".json".length());
                if (!KEPT.contains(structure)) {
                    Files.createDirectories(pack.resolve(name).getParent());
                    Files.writeString(pack.resolve(name), "{\"replace\": true, \"values\": []}\n");
                }
            }
        }
    }
}
