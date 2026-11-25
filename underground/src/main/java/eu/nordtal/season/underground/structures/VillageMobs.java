package eu.nordtal.season.underground.structures;

import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.NbtList;
import eu.nordtal.season.underground.anvil.Region;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/** Plans and creates starting iron golems and cats for populated villages. */
final class VillageMobs {

    static final String VILLAGE_PREFIX = "minecraft:village_";
    static final String VILLAGER = "minecraft:villager";
    static final String IRON_GOLEM = "minecraft:iron_golem";
    static final String CAT = "minecraft:cat";

    static final int VILLAGE_MARGIN = 8;
    static final int MIN_CATS = 2;

    private static final List<String> CAT_VARIANTS = List.of(
            "minecraft:black",
            "minecraft:british_shorthair",
            "minecraft:calico",
            "minecraft:jellie",
            "minecraft:persian",
            "minecraft:ragdoll",
            "minecraft:red",
            "minecraft:siamese",
            "minecraft:tabby",
            "minecraft:white");

    private static final List<String> CAT_SOUND_VARIANTS = List.of("minecraft:classic", "minecraft:royal");

    /** Existing village entities inside a village box. */
    record Population(List<double[]> villagers, int golems, int cats) {}

    /** Report callback matching Furnish's tab-separated report format. */
    @FunctionalInterface
    interface Reporter {
        void report(String kind, String at, String decision, String detail);
    }

    private final Path world;
    private final Area area;
    private final Random random;
    private final Map<Long, Map<Integer, Map<String, Object>>> entityCache = new HashMap<>();
    private final List<double[]> queuedGolems = new ArrayList<>();
    private final List<double[]> queuedCats = new ArrayList<>();

    VillageMobs(final Path world, final Area area, final Random random) {
        this.world = world;
        this.area = area;
        this.random = random;
    }

    /** Returns the target iron golem count for a village with the given villager count. */
    static int targetGolems(final int villagers) {
        if (villagers >= 10) {
            return 2;
        }
        return villagers >= 2 ? 1 : 0;
    }

    /** Returns how many additional starting cats a village needs. */
    static int neededCats(final int villagers, final int existingCats, final Random random) {
        if (villagers < 1 || existingCats >= MIN_CATS) {
            return 0;
        }
        return Math.max(0, MIN_CATS + random.nextInt(2) - existingCats);
    }

    /** Plans starting iron golems and cats for one village and passes each new entity to sink. */
    void populate(final Furnish.Place village, final Consumer<Map<String, Object>> sink, final Reporter reporter)
            throws IOException {
        final Box box = village.box();
        final String at = box.toString();
        final Population population = population(box.grow(VILLAGE_MARGIN, VILLAGE_MARGIN, VILLAGE_MARGIN));
        final int villagers = population.villagers().size();
        if (villagers == 0) {
            reporter.report("village-mobs", at, "skip", "0 villagers");
            return;
        }
        final int neededGolems = Math.max(0, targetGolems(villagers) - population.golems());
        final int neededCats = neededCats(villagers, population.cats(), random);
        if (neededGolems == 0 && neededCats == 0) {
            reporter.report(
                    "village-mobs",
                    at,
                    "skip",
                    villagers + " villagers, already has " + population.golems() + " golems and " + population.cats()
                            + " cats");
            return;
        }
        area.load(box, VILLAGE_MARGIN + 2);
        final double[] center = centroid(population.villagers());
        final Box search = new Box(
                box.minX() - VILLAGE_MARGIN,
                Math.max(-64, box.minY() - 4),
                box.minZ() - VILLAGE_MARGIN,
                box.maxX() + VILLAGE_MARGIN,
                Math.min(316, box.maxY() + 4),
                box.maxZ() + VILLAGE_MARGIN);
        final Set<List<Integer>> used = new HashSet<>();
        final List<double[]> golemSpots = golemSpots(search, center, neededGolems, used);
        final List<double[]> catSpots = catSpots(search, center, neededCats, used);
        final StringBuilder detail = new StringBuilder();
        detail.append(villagers)
                .append(" villagers, ")
                .append(golemSpots.size())
                .append(" golems, ")
                .append(catSpots.size())
                .append(" cats");
        for (final double[] spot : golemSpots) {
            queuedGolems.add(spot);
            sink.accept(ironGolem(spot));
            detail.append(String.format(Locale.ROOT, ", golem %.1f %.1f %.1f", spot[0], spot[1], spot[2]));
        }
        for (final double[] spot : catSpots) {
            queuedCats.add(spot);
            sink.accept(cat(spot));
            detail.append(String.format(Locale.ROOT, ", cat %.1f %.1f %.1f", spot[0], spot[1], spot[2]));
        }
        reporter.report(
                "village-mobs", at, golemSpots.isEmpty() && catSpots.isEmpty() ? "skip" : "spawn", detail.toString());
    }

    private Population population(final Box box) throws IOException {
        final List<double[]> villagers = new ArrayList<>();
        int golems = 0;
        int cats = 0;
        for (int regionX = box.minChunkX() >> 5; regionX <= box.maxChunkX() >> 5; regionX++) {
            for (int regionZ = box.minChunkZ() >> 5; regionZ <= box.maxChunkZ() >> 5; regionZ++) {
                final Map<Integer, Map<String, Object>> chunks = entityRegion(regionX, regionZ);
                for (final Map<String, Object> chunk : chunks.values()) {
                    for (final Object element : Nbt.list(chunk, "Entities")) {
                        final Map<String, Object> entity = Nbt.compound(element);
                        final NbtList pos = Nbt.list(entity, "Pos");
                        if (pos.size() != 3) {
                            continue;
                        }
                        final double x = (double) pos.get(0);
                        final double y = (double) pos.get(1);
                        final double z = (double) pos.get(2);
                        if (!box.holds((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
                            continue;
                        }
                        switch (Nbt.text(entity, "id")) {
                            case VILLAGER -> villagers.add(new double[] {x, y, z});
                            case IRON_GOLEM -> golems++;
                            case CAT -> cats++;
                            default -> {}
                        }
                    }
                }
            }
        }
        for (final double[] spot : queuedGolems) {
            if (box.holds((int) Math.floor(spot[0]), (int) Math.floor(spot[1]), (int) Math.floor(spot[2]))) {
                golems++;
            }
        }
        for (final double[] spot : queuedCats) {
            if (box.holds((int) Math.floor(spot[0]), (int) Math.floor(spot[1]), (int) Math.floor(spot[2]))) {
                cats++;
            }
        }
        return new Population(villagers, golems, cats);
    }

    private Map<Integer, Map<String, Object>> entityRegion(final int regionX, final int regionZ) throws IOException {
        final long key = Finds.key(regionX, regionZ);
        final Map<Integer, Map<String, Object>> cached = entityCache.get(key);
        if (cached != null) {
            return cached;
        }
        final Path file = Region.file(world.resolve("entities"), regionX, regionZ);
        final Map<Integer, Map<String, Object>> loaded = Files.exists(file) ? Region.read(file) : Map.of();
        entityCache.put(key, loaded);
        return loaded;
    }

    private List<double[]> golemSpots(
            final Box search, final double[] center, final int count, final Set<List<Integer>> used) {
        if (count <= 0) {
            return List.of();
        }
        final List<int[]> sky = new ArrayList<>();
        final List<int[]> covered = new ArrayList<>();
        final List<int[]> tight = new ArrayList<>();
        for (int x = search.minX(); x <= search.maxX(); x++) {
            for (int z = search.minZ(); z <= search.maxZ(); z++) {
                for (int y = search.minY(); y <= search.maxY(); y++) {
                    if (Planner.floor(area.name(x, y - 1, z)) && open3x3x3(x, y, z)) {
                        (skyOpen(x, y + 3, z) ? sky : covered).add(new int[] {x, y, z});
                    } else if (open2x3x2(x, y, z)) {
                        tight.add(new int[] {x, y, z});
                    }
                }
            }
        }
        sortByDistance(sky, center);
        sortByDistance(covered, center);
        sortByDistance(tight, center);
        final List<double[]> chosen = new ArrayList<>();
        pickSpread(sky, count, 16.0, 0.5, chosen, used);
        pickSpread(covered, count, 16.0, 0.5, chosen, used);
        pickSpread(sky, count, 1.0, 0.5, chosen, used);
        pickSpread(covered, count, 1.0, 0.5, chosen, used);
        pickSpread(tight, count, 1.0, 1.0, chosen, used);
        return chosen;
    }

    private List<double[]> catSpots(
            final Box search, final double[] center, final int count, final Set<List<Integer>> used) {
        if (count <= 0) {
            return List.of();
        }
        final List<int[]> sky = new ArrayList<>();
        final List<int[]> covered = new ArrayList<>();
        for (int x = search.minX(); x <= search.maxX(); x++) {
            for (int z = search.minZ(); z <= search.maxZ(); z++) {
                for (int y = search.minY(); y <= search.maxY(); y++) {
                    if (Planner.floor(area.name(x, y - 1, z))
                            && Planner.open(area.name(x, y, z))
                            && Planner.open(area.name(x, y + 1, z))) {
                        (skyOpen(x, y + 2, z) ? sky : covered).add(new int[] {x, y, z});
                    }
                }
            }
        }
        sortByDistance(sky, center);
        sortByDistance(covered, center);
        final List<double[]> chosen = new ArrayList<>();
        pickSpread(sky, count, 4.0, 0.5, chosen, used);
        pickSpread(covered, count, 4.0, 0.5, chosen, used);
        pickSpread(sky, count, 1.0, 0.5, chosen, used);
        pickSpread(covered, count, 1.0, 0.5, chosen, used);
        return chosen;
    }

    private static void pickSpread(
            final List<int[]> candidates,
            final int limit,
            final double minSpacingSq,
            final double offset,
            final List<double[]> chosen,
            final Set<List<Integer>> used) {
        for (final int[] cell : candidates) {
            if (chosen.size() >= limit) {
                return;
            }
            final List<Integer> key = List.of(cell[0], cell[1], cell[2]);
            if (used.contains(key)) {
                continue;
            }
            final double px = cell[0] + offset;
            final double py = cell[1];
            final double pz = cell[2] + offset;
            if (farEnough(px, py, pz, chosen, minSpacingSq)) {
                used.add(key);
                chosen.add(new double[] {px, py, pz});
            }
        }
    }

    private static boolean farEnough(
            final double x, final double y, final double z, final List<double[]> chosen, final double minSpacingSq) {
        for (final double[] other : chosen) {
            final double dx = x - other[0];
            final double dy = y - other[1];
            final double dz = z - other[2];
            if (dx * dx + dy * dy + dz * dz < minSpacingSq) {
                return false;
            }
        }
        return true;
    }

    private boolean open3x3x3(final int x, final int y, final int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (!Planner.open(area.name(x + dx, y + dy, z + dz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean open2x3x2(final int x, final int y, final int z) {
        boolean hasFloor = false;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                if (Planner.floor(area.name(x + dx, y - 1, z + dz))) {
                    hasFloor = true;
                }
                for (int dy = 0; dy <= 2; dy++) {
                    if (!Planner.open(area.name(x + dx, y + dy, z + dz))) {
                        return false;
                    }
                }
            }
        }
        return hasFloor;
    }

    private boolean skyOpen(final int x, final int fromY, final int z) {
        for (int y = fromY; y <= 319; y++) {
            final String name = area.name(x, y, z);
            if (!Planner.open(name) && !name.endsWith("_leaves")) {
                return false;
            }
        }
        return true;
    }

    private static void sortByDistance(final List<int[]> cells, final double[] center) {
        cells.sort(Comparator.comparingDouble(cell -> {
            final double dx = cell[0] + 0.5 - center[0];
            final double dy = cell[1] - center[1];
            final double dz = cell[2] + 0.5 - center[2];
            return dx * dx + dz * dz + 4.0 * dy * dy;
        }));
    }

    private static double[] centroid(final List<double[]> points) {
        double x = 0;
        double y = 0;
        double z = 0;
        for (final double[] point : points) {
            x += point[0];
            y += point[1];
            z += point[2];
        }
        final int n = points.size();
        return new double[] {x / n, y / n, z / n};
    }

    private Map<String, Object> ironGolem(final double[] spot) {
        final Map<String, Object> entity = baseEntity(IRON_GOLEM, spot);
        entity.put("PlayerCreated", (byte) 0);
        entity.put("PersistenceRequired", (byte) 1);
        return entity;
    }

    private Map<String, Object> cat(final double[] spot) {
        final Map<String, Object> entity = baseEntity(CAT, spot);
        entity.put("variant", CAT_VARIANTS.get(random.nextInt(CAT_VARIANTS.size())));
        entity.put("sound_variant", CAT_SOUND_VARIANTS.get(random.nextInt(CAT_SOUND_VARIANTS.size())));
        entity.put("PersistenceRequired", (byte) 1);
        return entity;
    }

    private Map<String, Object> baseEntity(final String id, final double[] spot) {
        final Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", id);
        final NbtList position = new NbtList(Nbt.DOUBLE);
        position.add(spot[0]);
        position.add(spot[1]);
        position.add(spot[2]);
        entity.put("Pos", position);
        final NbtList rotation = new NbtList(Nbt.FLOAT);
        rotation.add(random.nextFloat() * 360f);
        rotation.add(0f);
        entity.put("Rotation", rotation);
        entity.put("UUID", new int[] {random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt()});
        return entity;
    }
}
