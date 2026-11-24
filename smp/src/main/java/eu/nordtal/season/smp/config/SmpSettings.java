package eu.nordtal.season.smp.config;

import eu.nordtal.season.database.inbox.AreaShape;
import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.SettingRefused;
import eu.nordtal.season.smp.area.Polygons;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** The checks of smp's groups for what their specs cannot declare. */
public final class SmpSettings {

    private static final Set<String> MAP_ITEMS =
            Set.of("minecraft:buried_mineshaft_map", "minecraft:buried_ancient_city_map");

    private SmpSettings() {}

    public static void checkWheel(final WheelSpec config, final Predicate<String> knowsTag) {
        config.wheelPrizes().forEach(prize -> checkMap("wheel-prizes", prize, knowsTag));
    }

    public static void checkMilestones(
            final MilestonesSpec config, final Predicate<String> knowsTag, final Predicate<RewardItemSpec> validItem) {
        checkMilestones(config);
        config.milestones().stream()
                .flatMap(milestone -> milestone.objectives().stream())
                .flatMap(objective -> Stream.ofNullable(objective.rewardItem()).flatMap(Collection::stream))
                .forEach(reward -> {
                    checkMap("milestones", reward, knowsTag);
                    if (!validItem.test(reward)) {
                        throw new SettingRefused(
                                "milestones", SettingRule.VALID, "invalid reward item " + reward.item());
                    }
                });
    }

    private static void checkMap(final String setting, final RewardItemSpec reward, final Predicate<String> knowsTag) {
        if (reward.structure().isBlank()) {
            return;
        }
        if (!MAP_ITEMS.contains(reward.item()) || !reward.data().isBlank()) {
            throw new SettingRefused(setting, SettingRule.VALID, "a structure tag requires a buried explorer map");
        }
        if (!knowsTag.test(reward.structure())) {
            throw new SettingRefused(setting, SettingRule.VALID, "unknown structure tag " + reward.structure());
        }
    }

    /** Throws {@link SettingRefused} for a broken structure. It checks neither names nor stored progress. */
    public static void checkMilestones(final MilestonesSpec config) {
        final Milestones.Result result = Milestones.read(config);
        if (!result.problems().isEmpty()) {
            throw new SettingRefused(
                    "milestones", SettingRule.TRACK, "the milestone track is not usable:\n" + result.describe());
        }
    }

    /**
     * Throws {@link SettingRefused} for an area that cannot hold a player.
     * That is fewer than three corners, crossing edges, or a corner off the map in {@code overworld}.
     */
    public static void checkCustomBorder(final CustomBorderSpec config, final String overworld) {
        for (final CustomBorderSpec.World world : config.worlds()) {
            for (final CustomBorderSpec.Polygon polygon : world.polygons()) {
                final Polygons.Problem problem =
                        Polygons.problem(shapeOf(polygon), false, world.world().equals(overworld));
                if (problem != null) {
                    throw new SettingRefused(
                            "worlds.polygons." + problem.field(),
                            SettingRule.VALID,
                            problem.sentence() + " in " + world.world());
                }
            }
        }
    }

    /** Returns a border region as the area editor and its checks hold it, flat and without heights. */
    public static AreaShape shapeOf(final CustomBorderSpec.Polygon polygon) {
        return new AreaShape(
                polygon.name(),
                polygon.corners().stream()
                        .map(corner -> new AreaShape.Corner(corner.x(), corner.z()))
                        .toList(),
                0,
                0);
    }

    /** Throws {@link SettingRefused} for any protected area whose 3D polygon or height span is invalid. */
    public static void checkSpawn(final SpawnSpec config, final String overworld) {
        for (final SpawnRegionSpec region : config.protectedAreas()) {
            check3d("protected-areas", shapeOf(region), region.world(), overworld);
        }
    }

    /** Throws {@link SettingRefused} for any negotiation area or arena whose 3D polygon or height span is invalid. */
    public static void checkDuels(final DuelsSpec config, final String overworld) {
        for (final DuelAreaSpec area : config.negotiationArea()) {
            check3d("negotiation-area", shapeOf(area), area.world(), overworld);
        }
        for (final DuelAreaSpec area : config.arena()) {
            check3d("arena", shapeOf(area), area.world(), overworld);
        }
    }

    private static void check3d(
            final String setting, final AreaShape shape, final String world, final String overworld) {
        final Polygons.Problem problem = Polygons.problem(shape, true, world.equals(overworld));
        if (problem != null) {
            throw new SettingRefused(
                    setting + "." + problem.field(), SettingRule.VALID, problem.sentence() + " in " + world);
        }
    }

    /** Returns a spawn protection region as the area editor and its checks hold it. */
    public static AreaShape shapeOf(final SpawnRegionSpec region) {
        return new AreaShape(
                region.name(),
                region.corners().stream()
                        .map(corner -> new AreaShape.Corner(corner.x(), corner.z()))
                        .toList(),
                region.minY(),
                region.maxY());
    }

    /** Returns the {@code duels} group with its 3D area checks wired for {@code overworld}. */
    public static Group<DuelsSpec> duelsGroup(final String overworld) {
        return Group.of("duels", DuelsSpec.class)
                .checkedBy(spec -> checkDuels(spec, overworld))
                .whileRunning();
    }

    /** Returns the {@code spawn} group with its 3D area checks wired for {@code overworld}. */
    public static Group<SpawnSpec> spawnGroup(final String overworld) {
        return Group.of("spawn", SpawnSpec.class)
                .checkedBy(spec -> checkSpawn(spec, overworld))
                .whileRunning();
    }

    /** Returns the {@code custom-border} group with its 2D area checks wired for {@code overworld}. */
    public static Group<CustomBorderSpec> customBorderGroup(final String overworld) {
        return Group.of("custom-border", CustomBorderSpec.class)
                .checkedBy(spec -> checkCustomBorder(spec, overworld))
                .whileRunning();
    }

    /** Throws {@link SettingRefused} for duplicate coordinates or an auto-reset entry with zero delay. */
    public static void checkSpawnExceptions(final SpawnExceptionsSpec config) {
        final Set<String> seen = new HashSet<>();
        for (final SpawnExceptionSpec entry : config.entries()) {
            final String key = entry.world() + ":" + entry.x() + "," + entry.y() + "," + entry.z();
            if (!seen.add(key)) {
                throw new SettingRefused("entries", SettingRule.UNIQUE, key, "duplicate spawn exception at " + key);
            }
            if (entry.resetMode() == SpawnExceptionSpec.ResetMode.AUTO_RESET
                    && entry.resetMinutes() == 0
                    && entry.resetSeconds() == 0) {
                throw new SettingRefused(
                        "entries.reset-seconds",
                        SettingRule.POSITIVE,
                        "auto-reset requires at least 1 second at " + key);
            }
        }
    }

    /** Returns the {@code spawn-exceptions} group with its checks wired. */
    public static Group<SpawnExceptionsSpec> spawnExceptionsGroup() {
        return Group.of("spawn-exceptions", SpawnExceptionsSpec.class)
                .checkedBy(SmpSettings::checkSpawnExceptions)
                .whileRunning();
    }

    /** Returns a duel area as the area editor and its checks hold it. */
    public static AreaShape shapeOf(final DuelAreaSpec area) {
        return new AreaShape(
                area.name(),
                area.corners().stream()
                        .map(corner -> new AreaShape.Corner(corner.x(), corner.z()))
                        .toList(),
                area.minY(),
                area.maxY());
    }
}
