package eu.nordtal.season.underground.server;

import eu.nordtal.season.underground.anvil.Region;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Loads every chunk of a result on a scratch server and asks for the structures it must and must not have. */
public final class Verify {

    private static final Pattern PROBLEM = Pattern.compile(
            "ERROR|Exception|Couldn't load|Failed to|Ignoring unknown|wrong location|invalid",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTRY = Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2}");
    private static final Pattern BLOCK_ENTITY_DATA = Pattern.compile("BlockEntity] Serialization errors:");
    private static final Pattern BLOCK_POSITION = Pattern.compile("BlockPos\\{x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)}");
    private static final Pattern LOCATED = Pattern.compile("nearest|Could not find");
    private static final Pattern POSITION = Pattern.compile("\\[(-?\\d+), ~, (-?\\d+)]");
    private static final String PAPER_LIGHT = "starlight.light_version";

    private Verify() {}

    /**
     * Returns whether every chunk loaded without a complaint and the structures are as wanted.
     *
     * @param packs the folder with the data packs of the map, which name its biomes and structures
     * @param map the map the result was made from; a complaint about a block entity the map holds unchanged is
     *     the map's own and not counted as a problem
     */
    public static boolean run(
            final ScratchServer.Setup setup,
            final Path world,
            final @Nullable Path packs,
            final @Nullable Path map,
            final List<int[]> regions)
            throws IOException, InterruptedException {
        ScratchServer.prepare(setup);
        if (packs != null) {
            final Path folder = Files.createDirectories(setup.directory().resolve("world/datapacks"));
            try (DirectoryStream<Path> zips = Files.newDirectoryStream(packs, "*.zip")) {
                for (final Path zip : zips) {
                    Files.copy(zip, folder.resolve(zip.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        for (final String kind : new String[] {"region", "entities"}) {
            Files.createDirectories(setup.overworld().resolve(kind));
            for (final int[] region : regions) {
                final Path file = Region.file(world.resolve(kind), region[0], region[1]);
                if (Files.exists(file)) {
                    Files.copy(
                            file,
                            Region.file(setup.overworld().resolve(kind), region[0], region[1]),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (final int[] region : regions) {
            minX = Math.min(minX, region[0] * 512);
            minZ = Math.min(minZ, region[1] * 512);
            maxX = Math.max(maxX, region[0] * 512 + 511);
            maxZ = Math.max(maxZ, region[1] * 512 + 511);
        }
        final int[] area = {minX, minZ, maxX, maxZ};
        boolean good;
        // The stop saves every chunk; a save on command stalls the server until its watchdog dumps threads.
        final ScratchServer server = ScratchServer.start(setup);
        try {
            Generate.load(server, regions);
            good = located(server, "mineshaft", area, true);
            good &= located(server, "trial_chambers", area, true);
            good &= located(server, "stronghold", area, false);
        } finally {
            server.close();
        }
        final boolean clean = clean(server.log(), map, world);
        final boolean loaded = lit(setup.overworld(), regions);
        return good && clean && loaded;
    }

    /** Prints the lines of the log that name a problem and returns whether there are none. */
    private static boolean clean(final List<String> log, final @Nullable Path map, final Path world)
            throws IOException {
        int inherited = 0;
        final List<String> problems = new ArrayList<>();
        for (final List<String> entry : entries(log)) {
            if (entry.stream().noneMatch(line -> PROBLEM.matcher(line).find())) {
                continue;
            }
            if (map != null && inherited(entry, map, world)) {
                inherited++;
            } else {
                problems.addAll(entry);
            }
        }
        problems.stream()
                .limit(30)
                .forEach(line -> System.out.println("problem logged: line=\"" + line.replace('"', '\'') + "\""));
        System.out.println("problems counted: count=" + problems.size());
        if (map != null) {
            System.out.println("complaints about block entities of the map counted: count=" + inherited);
        }
        return problems.isEmpty();
    }

    /** Groups the lines of a log into entries: a line with a time and the lines below it that have none. */
    static List<List<String>> entries(final List<String> log) {
        final List<List<String>> entries = new ArrayList<>();
        for (final String line : log) {
            if (entries.isEmpty() || ENTRY.matcher(line).find()) {
                entries.add(new ArrayList<>());
            }
            entries.getLast().add(line);
        }
        return entries;
    }

    /**
     * Returns whether an entry of the log is the map's own complaint about block entities.
     * It is when it names only block entities that the result holds just as the map does.
     */
    static boolean inherited(final List<String> entry, final Path map, final Path result) throws IOException {
        if (!BLOCK_ENTITY_DATA.matcher(entry.getFirst()).find()) {
            return false;
        }
        boolean named = false;
        for (final String line : entry) {
            final Matcher position = BLOCK_POSITION.matcher(line);
            while (position.find()) {
                final int x = Integer.parseInt(position.group(1));
                final int y = Integer.parseInt(position.group(2));
                final int z = Integer.parseInt(position.group(3));
                final Map<String, Object> before = blockEntity(map, x, y, z);
                if (before == null || !same(before, blockEntity(result, x, y, z))) {
                    return false;
                }
                named = true;
            }
        }
        return named;
    }

    /** Returns the block entity at a position of a world, or null when there is none. */
    static @Nullable Map<String, Object> blockEntity(final Path world, final int x, final int y, final int z)
            throws IOException {
        final int index = (x >> 4 & 31) + (z >> 4 & 31) * Region.SIDE;
        final Map<String, Object> chunk = Region.read(
                        Region.file(world.resolve("region"), x >> 9, z >> 9), i -> i == index)
                .get(index);
        if (chunk == null || !(chunk.get("block_entities") instanceof List<?> entities)) {
            return null;
        }
        for (final Object entity : entities) {
            if (entity instanceof Map<?, ?> found
                    && Objects.equals(found.get("x"), x)
                    && Objects.equals(found.get("y"), y)
                    && Objects.equals(found.get("z"), z)) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> typed = (Map<String, Object>) found;
                return typed;
            }
        }
        return null;
    }

    /** Returns whether two values read from NBT are equal, arrays and nested compounds included. */
    static boolean same(final @Nullable Object a, final @Nullable Object b) {
        if (a instanceof Map<?, ?> left && b instanceof Map<?, ?> right) {
            return left.size() == right.size()
                    && left.entrySet().stream()
                            .allMatch(e -> right.containsKey(e.getKey()) && same(e.getValue(), right.get(e.getKey())));
        }
        if (a instanceof List<?> left && b instanceof List<?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!same(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Arrays.deepEquals(new Object[] {a}, new Object[] {b});
    }

    /**
     * Returns whether the server loaded every chunk.
     * The result comes without light, and a chunk has it again only once the server has loaded and saved it.
     */
    private static boolean lit(final Path overworld, final List<int[]> regions) throws IOException {
        int chunks = 0;
        int lit = 0;
        for (final int[] region : regions) {
            final Path file = Region.file(overworld.resolve("region"), region[0], region[1]);
            if (!Files.exists(file)) {
                continue;
            }
            for (final Map<String, Object> chunk : Region.read(file).values()) {
                chunks++;
                lit += lit(chunk) ? 1 : 0;
            }
        }
        System.out.println("light checked: lit=" + lit + " chunks=" + chunks);
        return lit == chunks;
    }

    /** Returns whether a chunk was saved with its light: the game marks it so, and Paper with a key of its own. */
    static boolean lit(final Map<String, Object> chunk) {
        return (chunk.get("isLightOn") instanceof Byte on && on != 0) || chunk.containsKey(PAPER_LIGHT);
    }

    /** Asks from the middle of the area for the nearest structure and compares where it is with what is wanted. */
    private static boolean located(
            final ScratchServer server, final String structure, final int[] area, final boolean wanted)
            throws IOException, InterruptedException {
        final String answer = server.ask(
                "execute in minecraft:overworld positioned " + (area[0] + area[2]) / 2 + " 0 " + (area[1] + area[3]) / 2
                        + " run locate structure minecraft:" + structure,
                LOCATED,
                Duration.ofMinutes(10));
        final Matcher position = POSITION.matcher(answer);
        final boolean inside = position.find()
                && Integer.parseInt(position.group(1)) >= area[0]
                && Integer.parseInt(position.group(1)) <= area[2]
                && Integer.parseInt(position.group(2)) >= area[1]
                && Integer.parseInt(position.group(2)) <= area[3];
        System.out.println("structure located: structure=" + structure + " inside=" + inside + " answer=\""
                + answer.replaceFirst("^.*?: ", "") + "\"");
        return inside == wanted;
    }
}
