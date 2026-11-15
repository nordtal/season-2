package eu.nordtal.season.underground.structures;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Decides which structure of the game each of the custom map's places becomes, and what its spawners do.
 * The module's README lists the rules.
 */
public final class Planner {

    /** The structure sets of the data pack, by the name of their file. */
    public static final String VILLAGES = "villages";

    public static final String OUTPOSTS = "pillager_outposts";
    public static final String MONUMENTS = "ocean_monuments";
    public static final String STRONGHOLDS = "strongholds";

    /** The share of a build's prismarine that must lie in the monument box the game puts around it. */
    static final double MONUMENT_FIT = 0.5;

    static final int MONUMENT_SIDE = 58;
    static final int MONUMENT_BOTTOM = 39;
    static final int MONUMENT_TOP = 61;

    /** How many stronghold blocks a cell of 16 cubed holds at least to count as stronghold. */
    static final int STRONGHOLD_CELL = 32;

    /** How far from a stronghold or a hive an empty spawner may be to belong to it. */
    static final int STRONGHOLD_REACH = 8;

    static final int HIVE_REACH = 4;

    /** How far above its stone bricks a stronghold's spawner may be: the map's strongholds rise to the surface. */
    static final int STRONGHOLD_ABOVE = 40;

    private static final List<String> ILLAGERS =
            List.of("minecraft:pillager", "minecraft:vindicator", "minecraft:evoker");

    private final Finds finds;
    private final Area area;
    private final Map<String, Reach> reaches;
    private final Random random;
    private final Plan plan = new Plan();
    private final List<Box> strongholds = new ArrayList<>();

    public Planner(final Finds finds, final Area area, final Map<String, Reach> reaches, final long seed) {
        this.finds = finds;
        this.area = area;
        this.reaches = reaches;
        this.random = new Random(seed);
    }

    /** Returns the plan for the whole world. */
    public Plan plan() throws IOException {
        strongholds();
        ships();
        monuments();
        villages();
        villagerSpawners();
        emptySpawners();
        return plan;
    }

    private void strongholds() throws IOException {
        final List<Clusters.At<Finds.Point>> frames = new ArrayList<>();
        finds.frames.forEach(frame -> frames.add(new Clusters.At<>(frame.x(), frame.y(), frame.z(), frame)));
        for (final List<Clusters.At<Finds.Point>> group : Clusters.of(frames, 32)) {
            final Box portal = Clusters.box(group);
            if (group.size() < 12) {
                report("stronghold", portal, "skip", group.size() + " portal frames, a portal room has 12");
                continue;
            }
            if (existing("minecraft:stronghold", portal)) {
                report("stronghold", portal, "skip", "a stronghold start is there already");
                continue;
            }
            area.load(portal, 192);
            final List<Box> cells = strongholdCells(portal, group.getFirst());
            Box around = cells.getFirst();
            for (final Box cell : cells) {
                around = around.union(cell);
                strongholds.add(cell.grow(STRONGHOLD_REACH, STRONGHOLD_REACH, STRONGHOLD_ABOVE));
            }
            final int starts = place("minecraft:stronghold", "minecraft:btp", STRONGHOLDS, cells);
            report("stronghold", around, "start", cells.size() + " pieces under " + starts + " starts");
        }
    }

    /** Returns the cells of 16 cubed that hold the stronghold around a portal, each tightened to its blocks. */
    private List<Box> strongholdCells(final Box portal, final Clusters.At<Finds.Point> start) {
        final Map<List<Integer>, @Nullable Box> seen = new HashMap<>();
        final ArrayDeque<List<Integer>> open = new ArrayDeque<>();
        final List<Box> cells = new ArrayList<>();
        open.add(List.of(start.x() >> 4, start.y() >> 4, start.z() >> 4));
        final int limitX = 192 / 16;
        while (!open.isEmpty()) {
            final List<Integer> cell = open.poll();
            if (seen.containsKey(cell)) {
                continue;
            }
            final @Nullable Box blocks = strongholdBlocks(cell.get(0), cell.get(1), cell.get(2));
            seen.put(cell, blocks);
            if (blocks == null) {
                continue;
            }
            cells.add(blocks);
            for (final int[] step : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                final List<Integer> next = List.of(cell.get(0) + step[0], cell.get(1) + step[1], cell.get(2) + step[2]);
                if (Math.abs(next.get(0) - (portal.minX() >> 4)) <= limitX
                        && Math.abs(next.get(2) - (portal.minZ() >> 4)) <= limitX
                        && next.get(1) >= -4
                        && next.get(1) < 20) {
                    open.add(next);
                }
            }
        }
        return cells;
    }

    private @Nullable Box strongholdBlocks(final int cellX, final int cellY, final int cellZ) {
        int count = 0;
        @Nullable Box box = null;
        for (int x = cellX * 16; x < cellX * 16 + 16; x++) {
            for (int z = cellZ * 16; z < cellZ * 16 + 16; z++) {
                for (int y = cellY * 16; y < cellY * 16 + 16; y++) {
                    if (strongholdBlock(area.name(x, y, z))) {
                        count++;
                        final Box block = new Box(x, y, z, x, y, z);
                        box = box == null ? block : box.union(block);
                    }
                }
            }
        }
        return count >= STRONGHOLD_CELL ? box : null;
    }

    static boolean strongholdBlock(final String name) {
        return name.contains("stone_brick")
                || name.startsWith("infested_")
                || name.equals("iron_bars")
                || name.equals("end_portal_frame");
    }

    private void ships() {
        final List<Clusters.At<Finds.Spawner>> illagers = new ArrayList<>();
        for (final Finds.Spawner spawner : finds.spawners) {
            if (!spawner.trial() && ILLAGERS.stream().anyMatch(spawner::spawns)) {
                illagers.add(new Clusters.At<>(spawner.x(), spawner.y(), spawner.z(), spawner));
            }
        }
        for (final List<Clusters.At<Finds.Spawner>> group : Clusters.of(illagers, 64)) {
            final Box ship = Clusters.box(group).grow(24, 8, 16);
            if (existing("minecraft:pillager_outpost", ship)) {
                report("ship", ship, "skip", "a pillager outpost start is there already");
                continue;
            }
            final int starts = place("minecraft:pillager_outpost", "minecraft:btp", OUTPOSTS, Reach.columns(ship));
            if (starts == 0) {
                report("ship", ship, "skip", "no candidate chunk in reach, so its pillager spawners stay");
                continue;
            }
            for (final Clusters.At<Finds.Spawner> at : group) {
                if (at.thing().spawns("minecraft:pillager")) {
                    plan.removals.add(new Plan.Removal(at.thing(), "pillager spawner of a ship"));
                }
            }
            report("ship", ship, "start", group.size() + " illager spawners");
        }
    }

    private void monuments() throws IOException {
        final List<Clusters.At<Finds.Spawner>> elders = new ArrayList<>();
        for (final Finds.Spawner spawner : finds.spawners) {
            if (spawner.trial() && spawner.spawns("minecraft:elder_guardian")) {
                elders.add(new Clusters.At<>(spawner.x(), spawner.y(), spawner.z(), spawner));
            }
        }
        for (final List<Clusters.At<Finds.Spawner>> group : Clusters.of(elders, 64)) {
            final Box spawners = Clusters.box(group);
            area.load(spawners, 48);
            final Blocks all = prismarine(spawners.grow(48, 32, 48), null);
            final @Nullable Box prismarine = all.box();
            if (prismarine == null) {
                report("monument", spawners, "skip", "no prismarine around the elder guardian spawners");
                continue;
            }
            final int middleX = (prismarine.minX() + prismarine.maxX()) / 2;
            final int middleZ = (prismarine.minZ() + prismarine.maxZ()) / 2;
            final Box monument = new Box(
                    middleX - MONUMENT_SIDE / 2,
                    MONUMENT_BOTTOM,
                    middleZ - MONUMENT_SIDE / 2,
                    middleX + MONUMENT_SIDE / 2 - 1,
                    MONUMENT_TOP,
                    middleZ + MONUMENT_SIDE / 2 - 1);
            final double share = prismarine(spawners.grow(48, 32, 48), monument).count() / (double) all.count();
            final String detail = String.format(
                    Locale.ROOT, "prismarine %s, %.0f%% of it in the box %s", prismarine, share * 100, monument);
            if (share < MONUMENT_FIT) {
                report("monument", prismarine, "skip", detail);
            } else if (existing("minecraft:monument", monument)) {
                report("monument", prismarine, "skip", "a monument start is there already");
            } else {
                final int starts = place("minecraft:monument", "minecraft:omb", MONUMENTS, List.of(monument));
                report("monument", prismarine, starts > 0 ? "start" : "skip", detail);
            }
        }
    }

    /** Some blocks: the box around them, null for none, and how many there are. */
    private record Blocks(@Nullable Box box, int count) {}

    /** Returns the box around the prismarine blocks of an area and their count, only those in a box if one is given. */
    private Blocks prismarine(final Box search, final @Nullable Box within) {
        int count = 0;
        @Nullable Box box = null;
        for (int x = search.minX(); x <= search.maxX(); x++) {
            for (int z = search.minZ(); z <= search.maxZ(); z++) {
                for (int y = Math.max(-64, search.minY()); y <= Math.min(319, search.maxY()); y++) {
                    if (area.name(x, y, z).contains("prismarine") && (within == null || within.holds(x, y, z))) {
                        count++;
                        final Box block = new Box(x, y, z, x, y, z);
                        box = box == null ? block : box.union(block);
                    }
                }
            }
        }
        return new Blocks(box, count);
    }

    private void villages() throws IOException {
        final List<Clusters.At<Boolean>> things = new ArrayList<>();
        finds.beds.forEach(bed -> things.add(new Clusters.At<>(bed.x(), bed.y(), bed.z(), true)));
        finds.chests.forEach(chest -> things.add(new Clusters.At<>(chest.x(), chest.y(), chest.z(), false)));
        for (final List<Clusters.At<Boolean>> group : Clusters.of(things, 48)) {
            final List<Clusters.At<Boolean>> beds =
                    group.stream().filter(Clusters.At::thing).toList();
            final Box place = Clusters.box(group);
            if (beds.isEmpty() || beds.size() == group.size()) {
                continue;
            }
            final @Nullable String hostile = hostile(place.grow(24, 24, 24));
            if (hostile != null) {
                report("village", place, "skip", beds.size() + " beds, but " + hostile);
                continue;
            }
            if (finds.starts.stream()
                    .anyMatch(start -> start.id().startsWith("minecraft:village_")
                            && start.box().meets(place))) {
                report("village", place, "skip", "a village start is there already");
                continue;
            }
            area.load(place, 8);
            village(place, beds);
        }
    }

    /** Returns why a place is no home for villagers: a trial spawner or an illager spawner in it, or null. */
    private @Nullable String hostile(final Box place) {
        for (final Finds.Spawner spawner : finds.spawners) {
            if (place.holds(spawner.x(), spawner.y(), spawner.z())
                    && (spawner.trial() || ILLAGERS.stream().anyMatch(spawner::spawns))) {
                return (spawner.trial() ? "a trial spawner" : "an illager spawner") + " at " + spawner.x() + " "
                        + spawner.y() + " " + spawner.z();
            }
        }
        return null;
    }

    private void village(final Box place, final List<Clusters.At<Boolean>> beds) {
        final Set<List<Integer>> used = new HashSet<>();
        final Map<String, Integer> types = new LinkedHashMap<>();
        final List<Plan.Villager> villagers = new ArrayList<>();
        for (final Clusters.At<Boolean> bed : beds) {
            final int @Nullable [] spot = spot(bed.x(), bed.y(), bed.z(), used);
            if (spot == null) {
                report(
                        "villager",
                        new Box(bed.x(), bed.y(), bed.z(), bed.x(), bed.y(), bed.z()),
                        "skip",
                        "no free cell beside the bed");
                continue;
            }
            final String type = villagerType(area.biome(bed.x(), bed.y(), bed.z()));
            types.merge(type, 1, Integer::sum);
            villagers.add(new Plan.Villager(spot[0] + 0.5, spot[1], spot[2] + 0.5, type));
        }
        if (villagers.isEmpty()) {
            report("village", place, "skip", beds.size() + " beds without a free cell beside them");
            return;
        }
        final String type = types.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("minecraft:plains");
        final String structure = "minecraft:village_" + villageKind(type);
        final int starts = place(structure, "minecraft:btp", VILLAGES, Reach.columns(place.grow(8, 4, 4)));
        if (starts == 0) {
            report("village", place, "skip", "no candidate chunk in reach, so it gets no villagers");
            return;
        }
        plan.villagers.addAll(villagers);
        report(
                "village",
                place,
                "start",
                structure + ", " + villagers.size() + " villagers for " + beds.size() + " beds, " + starts + " starts");
    }

    /** Returns the nearest free cell of two open blocks on a floor within three blocks of a bed, or null. */
    private int @Nullable [] spot(final int x, final int y, final int z, final Set<List<Integer>> used) {
        int @Nullable [] best = null;
        int bestDistance = 99;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    final int distance = Math.abs(dx) + Math.abs(dz) + 2 * Math.abs(dy);
                    final int px = x + dx;
                    final int py = y + dy;
                    final int pz = z + dz;
                    if (distance > 0
                            && distance < bestDistance
                            && open(area.name(px, py, pz))
                            && open(area.name(px, py + 1, pz))
                            && floor(area.name(px, py - 1, pz))
                            && !used.contains(List.of(px, py, pz))) {
                        best = new int[] {px, py, pz};
                        bestDistance = distance;
                    }
                }
            }
        }
        if (best != null) {
            used.add(List.of(best[0], best[1], best[2]));
        }
        return best;
    }

    static boolean open(final String name) {
        return name.equals("air")
                || name.equals("cave_air")
                || name.endsWith("carpet")
                || name.equals("short_grass")
                || name.equals("snow");
    }

    private static final List<String> NO_FLOOR = List.of(
            "fence",
            "wall",
            "door",
            "leaves",
            "torch",
            "sign",
            "banner",
            "pane",
            "campfire",
            "magma",
            "cactus",
            "berry",
            "button",
            "pressure",
            "flower",
            "candle",
            "lantern",
            "chain",
            "bars",
            "ladder",
            "vine",
            "fire");

    static boolean floor(final String name) {
        return !open(name)
                && !name.equals("water")
                && !name.equals("lava")
                && !name.endsWith("_bed")
                && NO_FLOOR.stream().noneMatch(name::contains);
    }

    /** Returns the villager type of a biome, by the words in its name. */
    static String villagerType(final @Nullable String biome) {
        final String name = biome == null ? "" : biome.substring(biome.indexOf(':') + 1);
        final String type;
        if (name.contains("desert")) {
            type = "desert";
        } else if (name.contains("jungle")) {
            type = "jungle";
        } else if (name.contains("savanna") || name.contains("badlands")) {
            type = "savanna";
        } else if (name.contains("snow") || name.contains("frozen") || name.contains("ice")) {
            type = "snow";
        } else if (name.contains("swamp") || name.contains("mangrove")) {
            type = "swamp";
        } else if (name.contains("taiga")) {
            type = "taiga";
        } else {
            type = "plains";
        }
        return "minecraft:" + type;
    }

    /** Returns the kind of village structure for a villager type: there is none for jungle and swamp. */
    static String villageKind(final String type) {
        return switch (type) {
            case "minecraft:desert" -> "desert";
            case "minecraft:savanna" -> "savanna";
            case "minecraft:snow" -> "snowy";
            case "minecraft:taiga" -> "taiga";
            default -> "plains";
        };
    }

    private void villagerSpawners() {
        for (final Finds.Spawner spawner : finds.spawners) {
            if (!spawner.trial() && spawner.spawns("minecraft:villager")) {
                plan.removals.add(new Plan.Removal(spawner, "villager spawner"));
            }
        }
    }

    private void emptySpawners() {
        final Set<Finds.Hive> filled = new HashSet<>();
        for (final Finds.Spawner spawner : finds.spawners) {
            if (spawner.trial() || !spawner.mobs().isEmpty()) {
                continue;
            }
            final Box at = new Box(spawner.x(), spawner.y(), spawner.z(), spawner.x(), spawner.y(), spawner.z());
            if (strongholds.stream().anyMatch(box -> box.holds(spawner.x(), spawner.y(), spawner.z()))) {
                plan.retypes.add(new Plan.Retype(spawner, "minecraft:skeleton"));
                report("empty spawner", at, "skeleton", "in a stronghold");
                continue;
            }
            final List<Finds.Hive> near = finds.hives.stream()
                    .filter(hive -> Math.max(
                                    Math.abs(hive.x() - spawner.x()),
                                    Math.max(Math.abs(hive.y() - spawner.y()), Math.abs(hive.z() - spawner.z())))
                            <= HIVE_REACH)
                    .toList();
            if (near.isEmpty()) {
                report("empty spawner", at, "keep", "no bee nest or hive within " + HIVE_REACH + " blocks");
                continue;
            }
            plan.removals.add(new Plan.Removal(spawner, "empty spawner beside a hive"));
            if (near.stream().noneMatch(hive -> hive.bees() > 0 || filled.contains(hive))) {
                final Finds.Hive hive = near.getFirst();
                filled.add(hive);
                plan.bees.add(new Plan.Bees(hive, 2 + random.nextInt(2)));
                report(
                        "empty spawner",
                        at,
                        "remove",
                        "bees added to the hive at " + hive.x() + " " + hive.y() + " " + hive.z());
            } else {
                report("empty spawner", at, "remove", "the hive beside it holds bees");
            }
        }
    }

    /** Returns whether a start of a structure in the world already meets a box. */
    private boolean existing(final String structure, final Box box) {
        return finds.starts.stream()
                .anyMatch(start -> start.id().equals(structure) && start.box().meets(box));
    }

    /** Puts pieces under starts of a structure in the candidate chunks of its set, returning how many starts. */
    private int place(final String structure, final String piece, final String set, final List<Box> pieces) {
        final Reach reach = reaches.get(set);
        if (reach == null) {
            throw new IllegalStateException("The data pack has no structure set " + set + ".");
        }
        final List<Reach.Choice> choices = reach.cover(pieces);
        int covered = 0;
        for (final Reach.Choice choice : choices) {
            plan.start(structure, choice.chunkX(), choice.chunkZ(), piece)
                    .pieces()
                    .addAll(choice.pieces());
            covered += choice.pieces().size();
        }
        if (covered < pieces.size()) {
            report(
                    structure,
                    pieces.getFirst(),
                    "partly",
                    (pieces.size() - covered) + " pieces without a full candidate chunk in reach");
        }
        return choices.size();
    }

    private void report(final String kind, final Box box, final String decision, final String detail) {
        plan.report.add(kind + "\t" + box + "\t" + decision + "\t" + detail);
    }
}
