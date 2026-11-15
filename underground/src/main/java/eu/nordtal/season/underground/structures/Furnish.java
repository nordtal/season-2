package eu.nordtal.season.underground.structures;

import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.NbtList;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.block.Blocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The furnish command: allays for outposts, and loot for suspicious blocks and the chests of outposts and villages.
 * The module's README lists the rules.
 */
public final class Furnish {

    /** The name of the report the command writes beside the changed files. */
    public static final String REPORT = "furnish-report.tsv";

    static final String OUTPOST = "minecraft:pillager_outpost";
    static final String TAIGA = "minecraft:village_taiga";
    static final String OUTPOST_LOOT = "minecraft:chests/pillager_outpost";
    static final String TAIGA_LOOT = "minecraft:chests/village/village_taiga_house";

    /** How many allays an outpost gets. */
    static final int ALLAYS = 2;

    /** The fewest open cells a closed room holds, so a gap under a stair is none. */
    static final int ROOM = 8;

    /** The share of a room's walls that must be built, so a closed cave below a ship is no room. */
    static final double BUILT_WALLS = 0.5;

    /** How far around an outpost an allay counts as one of its own on a second run. */
    static final int ALLAY_REACH = 8;

    private static final String REGION = "region";
    private static final String ENTITIES = "entities";

    /** What the command did. */
    public record Summary(
            int regionFiles,
            int entityFiles,
            int outposts,
            int villages,
            int chests,
            int allays,
            int missing,
            Map<String, Integer> suspicious) {

        @Override
        public String toString() {
            final StringBuilder text = new StringBuilder("regionFiles=" + regionFiles + " entityFiles=" + entityFiles
                    + " outposts=" + outposts + " villages=" + villages + " chests=" + chests + " allays=" + allays
                    + " missing=" + missing + " suspicious="
                    + suspicious.values().stream().mapToInt(Integer::intValue).sum());
            suspicious.forEach((table, count) -> text.append(' ')
                    .append(table.substring(table.lastIndexOf('/') + 1))
                    .append('=')
                    .append(count));
            return text.toString();
        }
    }

    /** A place of the map: the starts of one structure whose pieces touch, and the box around them. */
    record Place(String structure, Box box) {}

    /** A change to one chunk, returning whether it changed anything. */
    @FunctionalInterface
    private interface Change {
        boolean apply(Map<String, Object> chunk);
    }

    private final Path world;
    private final Path out;
    private final Finds finds;
    private final Set<String> regions;
    private final int dataVersion;
    private final Random random;
    private final Area area;
    private final Blocks blocks = Blocks.load();
    private final Map<String, Map<Integer, List<Change>>> files = new TreeMap<>();
    private final List<String> report = new ArrayList<>();
    private final Map<String, Integer> suspicious = new TreeMap<>();
    private int chests;
    private int allays;
    private int missing;

    private Furnish(final Path world, final Path out, final Finds finds, final long seed, final List<int[]> regions) {
        this.world = world;
        this.out = out;
        this.finds = finds;
        this.regions = names(regions);
        this.dataVersion = Structures.version(finds);
        this.random = new Random(seed);
        this.area = new Area(world);
    }

    /** Runs the command on a world and writes every region and entity file it changes, with the report, below out. */
    public static String run(final Path world, final long seed, final Path out, final List<int[]> regions)
            throws IOException {
        final Finds finds = Scan.run(world, regions);
        return new Furnish(world, out, finds, seed, regions).furnish().toString();
    }

    private Summary furnish() throws IOException {
        final List<Place> outposts = places(finds.starts, OUTPOST);
        final List<Place> villages = places(finds.starts, TAIGA);
        for (final Place outpost : outposts) {
            chests(outpost, OUTPOST_LOOT);
            allays(outpost);
        }
        for (final Place village : villages) {
            chests(village, TAIGA_LOOT);
        }
        suspicious();
        for (final Map.Entry<String, Map<Integer, List<Change>>> file : files.entrySet()) {
            versions(file.getKey(), file.getValue().keySet());
        }
        int regionFiles = 0;
        int entityFiles = 0;
        for (final Map.Entry<String, Map<Integer, List<Change>>> file : files.entrySet()) {
            if (apply(file.getKey(), file.getValue())) {
                if (file.getKey().startsWith(REGION)) {
                    regionFiles++;
                } else {
                    entityFiles++;
                }
            }
        }
        final List<String> lines = new ArrayList<>();
        lines.add("kind\tat\tdecision\tdetail");
        lines.addAll(report);
        Files.createDirectories(out);
        Files.write(out.resolve(REPORT), lines, StandardCharsets.UTF_8);
        return new Summary(
                regionFiles, entityFiles, outposts.size(), villages.size(), chests, allays, missing, suspicious);
    }

    /** Joins the starts of a structure whose boxes touch into places, as a wide place has several starts. */
    static List<Place> places(final List<Finds.Start> starts, final String structure) {
        final List<Box> boxes = new ArrayList<>(starts.stream()
                .filter(start -> start.id().equals(structure))
                .map(Finds.Start::box)
                .toList());
        boolean joined = true;
        while (joined) {
            joined = false;
            for (int i = 0; i < boxes.size() && !joined; i++) {
                for (int j = i + 1; j < boxes.size() && !joined; j++) {
                    if (boxes.get(i).grow(1, 1, 1).meets(boxes.get(j))) {
                        boxes.set(i, boxes.get(i).union(boxes.remove(j)));
                        joined = true;
                    }
                }
            }
        }
        boxes.sort(Comparator.comparingInt(Box::minX).thenComparingInt(Box::minZ));
        return boxes.stream().map(box -> new Place(structure, box)).toList();
    }

    /**
     * Gives the place's loot table to its empty chests that roll none or another village's house table.
     * A place left without a chest of its table gets it on one empty chest of its commonest table, nearest the middle.
     */
    private void chests(final Place place, final String table) {
        final Box box = place.box();
        final List<Finds.Chest> inside = finds.loot.stream()
                .filter(chest -> box.holds(chest.x(), chest.y(), chest.z()))
                .sorted(Comparator.comparingInt(Finds.Chest::x)
                        .thenComparingInt(Finds.Chest::z)
                        .thenComparingInt(Finds.Chest::y))
                .toList();
        final List<Finds.Chest> chosen = new ArrayList<>();
        for (final Finds.Chest chest : inside) {
            if (chest.stacks() == 0 && (chest.lootTable() == null || otherHouse(chest.lootTable(), table))) {
                chosen.add(chest);
            }
        }
        final boolean has = inside.stream().anyMatch(chest -> table.equals(chest.lootTable()));
        if (chosen.isEmpty() && !has) {
            final Finds.@Nullable Chest one = commonest(inside, box);
            if (one == null) {
                report("chests", box.toString(), "none", inside.size() + " chests, each holds items or none is there");
            } else {
                chosen.add(one);
            }
        }
        report(
                "chests",
                box.toString(),
                has && chosen.isEmpty() ? "skip" : "table",
                place.structure() + ", " + inside.size() + " chests, " + chosen.size() + " get " + table
                        + (has ? ", has one already" : ""));
        for (final Finds.Chest chest : chosen) {
            final long seed = random.nextLong();
            add(REGION, chest.x() >> 4, chest.z() >> 4, chunk -> {
                final String decision = loot(chunk, chest, table, seed);
                report(
                        "chest",
                        chest.x() + " " + chest.y() + " " + chest.z(),
                        decision,
                        place.structure() + ", rolled " + chest.lootTable() + " before");
                if (decision.equals(table)) {
                    chests++;
                    return true;
                }
                return false;
            });
        }
    }

    /** Gives every suspicious block that rolls nothing the table of its place, with a report line per chunk. */
    private void suspicious() {
        for (final Map.Entry<Long, Integer> place :
                Archaeology.places(finds.suspicious).entrySet()) {
            final int chunkX = (int) (place.getKey() >> 32);
            final int chunkZ = (int) (long) place.getKey();
            final boolean large = place.getValue() >= Archaeology.LARGE;
            add(REGION, chunkX, chunkZ, chunk -> {
                final Map<String, Integer> set = new TreeMap<>();
                final boolean changed = Archaeology.dig(chunk, large, random, set);
                set.forEach((table, count) -> suspicious.merge(table, count, Integer::sum));
                report(
                        "suspicious",
                        chunkX + " " + chunkZ,
                        changed ? "table" : "skip",
                        "place of " + place.getValue() + " blocks" + (set.isEmpty() ? "" : ", " + set));
                return changed;
            });
        }
    }

    /** Returns whether a table is the house table of another kind of village than the table to set. */
    static boolean otherHouse(final String old, final String table) {
        return old.startsWith("minecraft:chests/village/village_")
                && old.endsWith("_house")
                && table.endsWith("_house")
                && !old.equals(table);
    }

    /** Returns the chest that holds nothing, of the table most chests of a place roll, nearest its middle, or null. */
    static Finds.@Nullable Chest commonest(final List<Finds.Chest> chests, final Box box) {
        final Map<String, Integer> counts = new TreeMap<>();
        for (final Finds.Chest chest : chests) {
            if (chest.stacks() == 0 && chest.lootTable() != null) {
                counts.merge(chest.lootTable(), 1, Integer::sum);
            }
        }
        final @Nullable String most = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        if (most == null) {
            return null;
        }
        final double middleX = (box.minX() + box.maxX()) / 2.0;
        final double middleZ = (box.minZ() + box.maxZ()) / 2.0;
        return chests.stream()
                .filter(chest -> chest.stacks() == 0 && most.equals(chest.lootTable()))
                .min(Comparator.comparingDouble(
                        chest -> Math.pow(chest.x() - middleX, 2) + Math.pow(chest.z() - middleZ, 2)))
                .orElse(null);
    }

    /** Sets a loot table on a chest that still holds nothing and rolls what the scan read, or says why not. */
    static String loot(final Map<String, Object> chunk, final Finds.Chest at, final String table, final long seed) {
        for (final Object element : Nbt.list(chunk, "block_entities")) {
            final Map<String, Object> entity = Nbt.compound(element);
            if (Nbt.number(entity, "x", 0) != at.x()
                    || Nbt.number(entity, "y", 0) != at.y()
                    || Nbt.number(entity, "z", 0) != at.z()) {
                continue;
            }
            final @Nullable String old = entity.get("LootTable") instanceof String text ? text : null;
            if (!Objects.equals(old, at.lootTable())) {
                return "keep: has " + old + " now";
            }
            if (!Nbt.list(entity, "Items").isEmpty()) {
                return "keep: holds " + Nbt.list(entity, "Items").size() + " stacks";
            }
            entity.put("LootTable", table);
            entity.put("LootTableSeed", seed);
            return table;
        }
        return "keep: no chest there";
    }

    /**
     * Places the allays of an outpost in its largest closed room, else on a lead to its fence nearest the middle.
     * Without either they go free on its highest floor near the middle.
     */
    private void allays(final Place outpost) throws IOException {
        final Box box = outpost.box();
        final String at = box.toString();
        final int already = allaysAround(box.grow(ALLAY_REACH, ALLAY_REACH, ALLAY_REACH));
        if (already > 0) {
            report("allays", at, "skip", already + " allays are there already");
            return;
        }
        area.load(box, 2);
        final @Nullable Room room = room(box);
        final List<double[]> spots = new ArrayList<>();
        final String decision;
        int @Nullable [] post = null;
        if (room != null) {
            for (final int[] cell : room.spots(ALLAYS)) {
                spots.add(new double[] {cell[0] + 0.5, cell[1], cell[2] + 0.5});
            }
            decision = "room";
        } else if ((post = post(box)) != null) {
            for (int i = 0; i < ALLAYS; i++) {
                spots.add(new double[] {post[3] + 0.5, post[4], post[5] + 0.5});
            }
            decision = "leashed";
        } else {
            final int @Nullable [] deck = deck(box);
            if (deck == null) {
                report("allays", at, "skip", "no room, no fence and no free floor");
                return;
            }
            for (int i = 0; i < ALLAYS; i++) {
                spots.add(new double[] {deck[0] + 0.5, deck[1] + 1.0, deck[2] + 0.5});
            }
            decision = "free";
        }
        final StringBuilder detail = new StringBuilder();
        if (room != null) {
            detail.append(room.size()).append(" cells, ");
        }
        if (post != null) {
            detail.append("on a lead to the fence at " + post[0] + " " + post[1] + " " + post[2] + ", ");
        }
        for (final double[] spot : spots) {
            final Map<String, Object> allay = allay(spot);
            if (post != null) {
                allay.put("leash", new int[] {post[0], post[1], post[2]});
            }
            final int chunkX = (int) Math.floor(spot[0]) >> 4;
            final int chunkZ = (int) Math.floor(spot[2]) >> 4;
            add(ENTITIES, chunkX, chunkZ, chunk -> {
                entities(chunk, chunkX, chunkZ).add(allay);
                allays++;
                return true;
            });
            detail.append(String.format(Locale.ROOT, "allay at %.1f %.1f %.1f ", spot[0], spot[1], spot[2]));
        }
        report("allays", at, decision, detail.toString().strip());
    }

    /** Counts the allays the entity files hold inside a box. */
    private int allaysAround(final Box box) throws IOException {
        int count = 0;
        final Path entities = world.resolve(ENTITIES);
        for (int regionX = box.minChunkX() >> 5; regionX <= box.maxChunkX() >> 5; regionX++) {
            for (int regionZ = box.minChunkZ() >> 5; regionZ <= box.maxChunkZ() >> 5; regionZ++) {
                final Path file = Region.file(entities, regionX, regionZ);
                if (!Files.exists(file)) {
                    continue;
                }
                for (final Map<String, Object> chunk : Region.read(file).values()) {
                    for (final Object element : Nbt.list(chunk, "Entities")) {
                        final Map<String, Object> entity = Nbt.compound(element);
                        final NbtList pos = Nbt.list(entity, "Pos");
                        if (Nbt.text(entity, "id").equals("minecraft:allay")
                                && pos.size() == 3
                                && box.holds(
                                        (int) Math.floor((double) pos.get(0)),
                                        (int) Math.floor((double) pos.get(1)),
                                        (int) Math.floor((double) pos.get(2)))) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    /** A closed room: its open cells. */
    record Room(List<int[]> cells) {

        int size() {
            return cells.size();
        }

        /** Returns cells near the middle of the room with open space above them, as many as asked where it has them. */
        List<int[]> spots(final int count) {
            double x = 0;
            double y = 0;
            double z = 0;
            for (final int[] cell : cells) {
                x += cell[0];
                y += cell[1];
                z += cell[2];
            }
            final double mx = x / cells.size();
            final double my = y / cells.size();
            final double mz = z / cells.size();
            final List<int[]> sorted = new ArrayList<>(cells);
            sorted.sort(Comparator.comparingDouble(
                    cell -> Math.pow(cell[0] - mx, 2) + Math.pow(cell[1] - my, 2) + Math.pow(cell[2] - mz, 2)));
            final List<int[]> spots = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                spots.add(sorted.get(Math.min(i, sorted.size() - 1)));
            }
            return spots;
        }
    }

    /** Returns the largest room inside a box whose open cells reach no side of it and whose walls are mostly built. */
    @Nullable
    Room room(final Box box) {
        final Box search = new Box(
                box.minX(), Math.max(-64, box.minY()), box.minZ(), box.maxX(), Math.min(319, box.maxY()), box.maxZ());
        final int sizeX = search.maxX() - search.minX() + 1;
        final int sizeY = search.maxY() - search.minY() + 1;
        final int sizeZ = search.maxZ() - search.minZ() + 1;
        final boolean[] seen = new boolean[sizeX * sizeY * sizeZ];
        @Nullable Room best = null;
        for (int i = 0; i < seen.length; i++) {
            if (seen[i]) {
                continue;
            }
            final int x = search.minX() + i % sizeX;
            final int z = search.minZ() + (i / sizeX) % sizeZ;
            final int y = search.minY() + i / (sizeX * sizeZ);
            if (!passable(area.name(x, y, z))) {
                seen[i] = true;
                continue;
            }
            final List<int[]> cells = new ArrayList<>();
            boolean closed = true;
            int walls = 0;
            int built = 0;
            final ArrayDeque<int[]> open = new ArrayDeque<>();
            seen[i] = true;
            open.add(new int[] {x, y, z});
            while (!open.isEmpty()) {
                final int[] cell = open.poll();
                cells.add(cell);
                for (final int[] step : STEPS) {
                    final int nx = cell[0] + step[0];
                    final int ny = cell[1] + step[1];
                    final int nz = cell[2] + step[2];
                    if (!search.holds(nx, ny, nz)) {
                        closed = false;
                        continue;
                    }
                    final String name = area.name(nx, ny, nz);
                    final int index =
                            (nx - search.minX()) + (nz - search.minZ()) * sizeX + (ny - search.minY()) * sizeX * sizeZ;
                    if (passable(name)) {
                        if (!seen[index]) {
                            seen[index] = true;
                            open.add(new int[] {nx, ny, nz});
                        }
                    } else {
                        walls++;
                        if (built(name)) {
                            built++;
                        }
                    }
                }
            }
            final boolean larger = best == null || cells.size() > best.size();
            if (closed && cells.size() >= ROOM && built >= walls * BUILT_WALLS && larger) {
                best = new Room(cells);
            }
        }
        return best;
    }

    private static final int[][] STEPS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * Returns the fence nearest the middle of a box with an open cell beside or above it, or null.
     * The result is the fence's position and the open cell's, the higher fence first. The game ties a knot to the
     * fence when it loads an allay leashed to it.
     */
    int @Nullable [] post(final Box box) {
        final double middleX = (box.minX() + box.maxX()) / 2.0;
        final double middleZ = (box.minZ() + box.maxZ()) / 2.0;
        int @Nullable [] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = Math.min(318, box.maxY()); y >= Math.max(-64, box.minY()); y--) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    if (!area.name(x, y, z).endsWith("_fence")) {
                        continue;
                    }
                    final double distance = Math.pow(x - middleX, 2) + Math.pow(z - middleZ, 2);
                    if (distance >= bestDistance) {
                        continue;
                    }
                    for (final int[] step : BESIDE) {
                        final int cx = x + step[0];
                        final int cy = y + step[1];
                        final int cz = z + step[2];
                        if (box.holds(cx, cy, cz) && open(area.name(cx, cy, cz))) {
                            best = new int[] {x, y, z, cx, cy, cz};
                            bestDistance = distance;
                            break;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static final int[][] BESIDE = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Returns whether a block is empty to fly in: air or a block one walks through. */
    private static boolean open(final String name) {
        return name.equals("air") || name.equals("cave_air") || name.equals("light");
    }

    /** Returns the highest floor block inside a box with two open blocks above it, nearest its middle, or null. */
    int @Nullable [] deck(final Box box) {
        final int middleX = (box.minX() + box.maxX()) / 2;
        final int middleZ = (box.minZ() + box.maxZ()) / 2;
        final int reach = Math.max(box.maxX() - box.minX(), box.maxZ() - box.minZ()) / 2 + 1;
        for (int ring = 0; ring <= reach; ring++) {
            int @Nullable [] best = null;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring
                            || !box.holds(middleX + dx, box.minY(), middleZ + dz)) {
                        continue;
                    }
                    final int[] floor = floor(middleX + dx, middleZ + dz, box);
                    if (floor != null && (best == null || floor[1] > best[1])) {
                        best = floor;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** Returns the highest built floor of a column inside a box with two open blocks above it, or null. */
    private int @Nullable [] floor(final int x, final int z, final Box box) {
        for (int y = Math.min(318, box.maxY()) - 2; y >= Math.max(-64, box.minY()); y--) {
            final String name = area.name(x, y, z);
            if (passable(name)) {
                continue;
            }
            if (Planner.floor(name)
                    && built(name)
                    && passable(area.name(x, y + 1, z))
                    && passable(area.name(x, y + 2, z))) {
                return new int[] {x, y, z};
            }
            return null;
        }
        return null;
    }

    /** Returns whether a block of the map counts as built, or may be. */
    private boolean built(final String name) {
        final int kind = blocks.mapCell("minecraft:" + name) & Blocks.KIND;
        return kind == Blocks.BUILT || kind == Blocks.AMBIGUOUS;
    }

    private static final List<String> THROUGH = List.of(
            "torch",
            "sign",
            "banner",
            "carpet",
            "button",
            "pressure_plate",
            "rail",
            "lever",
            "tripwire",
            "redstone_wire",
            "short_grass",
            "tall_grass",
            "fern",
            "candle",
            "lantern",
            "flower_pot",
            "chain",
            "end_rod",
            "lightning_rod",
            "ladder",
            "vine",
            "door",
            "fence_gate",
            "slab",
            "stairs",
            "bed",
            "campfire",
            "head",
            "skull",
            "daylight_detector",
            "cake",
            "snow",
            "cobweb",
            "sapling",
            "flower",
            "mushroom",
            "hanging_roots",
            "bush",
            "grass",
            "sugar_cane",
            "lily_pad",
            "water",
            "bubble_column",
            "seagrass",
            "kelp",
            "kelp_plant",
            "coral",
            "coral_fan");

    /**
     * Returns whether an allay may pass a block.
     * It passes air, water, plants, the blocks one walks through, thin blocks it passes beside, slabs and stairs,
     * whose half-open cells join into a gap, and doors, trapdoors and gates, which a player leaves open.
     */
    static boolean passable(final String name) {
        return name.equals("air")
                || name.equals("cave_air")
                || name.equals("void_air")
                || name.equals("light")
                || THROUGH.stream().anyMatch(name::endsWith);
    }

    private Map<String, Object> allay(final double[] spot) {
        final Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", "minecraft:allay");
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
        entity.put("PersistenceRequired", (byte) 1);
        return entity;
    }

    private NbtList entities(final Map<String, Object> chunk, final int chunkX, final int chunkZ) {
        if (chunk.isEmpty()) {
            chunk.put("DataVersion", dataVersion);
            chunk.put("Position", new int[] {chunkX, chunkZ});
        }
        if (!(chunk.get("Entities") instanceof NbtList)) {
            chunk.put("Entities", new NbtList(Nbt.COMPOUND));
        }
        return Nbt.list(chunk, "Entities");
    }

    /** Returns the names x.z of the regions the command may change. */
    static Set<String> names(final List<int[]> regions) {
        final Set<String> names = new HashSet<>();
        for (final int[] region : regions) {
            names.add(region[0] + "." + region[1]);
        }
        return names;
    }

    private void add(final String kind, final int chunkX, final int chunkZ, final Change change) {
        final String region = (chunkX >> 5) + "." + (chunkZ >> 5);
        if (!regions.contains(region)) {
            report("outside", "r." + region, "skip", kind + " of the chunk " + chunkX + " " + chunkZ + " stays");
            return;
        }
        files.computeIfAbsent(kind + "/r." + region + ".mca", key -> new TreeMap<>())
                .computeIfAbsent((chunkX & 31) + (chunkZ & 31) * Region.SIDE, key -> new ArrayList<>())
                .add(change);
    }

    /** Applies the changes of one file and writes it below out when one changed anything, returning whether. */
    private boolean apply(final String name, final Map<Integer, List<Change>> changes) throws IOException {
        final Path source = world.resolve(name);
        final Map<Integer, Map<String, Object>> chunks =
                Files.exists(source) ? new HashMap<>(Region.read(source)) : new HashMap<>();
        boolean changed = false;
        for (final Map.Entry<Integer, List<Change>> entry : changes.entrySet()) {
            @Nullable Map<String, Object> chunk = chunks.get(entry.getKey());
            if (chunk == null && name.startsWith(ENTITIES)) {
                chunk = new LinkedHashMap<>();
                chunks.put(entry.getKey(), chunk);
            }
            if (chunk == null) {
                missing += entry.getValue().size();
                report("missing", name, "skip", "the chunk " + entry.getKey() + " is not there");
                continue;
            }
            for (final Change change : entry.getValue()) {
                changed |= change.apply(chunk);
            }
        }
        if (changed) {
            Region.write(out.resolve(name), chunks);
        }
        return changed;
    }

    /** Throws before anything is written when a chunk to change is at another data version than the world. */
    private void versions(final String name, final Set<Integer> chunks) throws IOException {
        final Path source = world.resolve(name);
        if (!Files.exists(source)) {
            return;
        }
        final Map<Integer, Map<String, Object>> read = Region.read(source);
        for (final int index : chunks) {
            final @Nullable Map<String, Object> chunk = read.get(index);
            final int version = chunk == null ? dataVersion : Nbt.number(chunk, "DataVersion", dataVersion);
            if (version != dataVersion) {
                throw new IllegalStateException("The chunk " + index + " of " + name + " is at data version " + version
                        + ", not " + dataVersion + "; upgrade the world first.");
            }
        }
    }

    private void report(final String kind, final String at, final String decision, final String detail) {
        report.add(kind + "\t" + at + "\t" + decision + "\t" + detail);
    }
}
