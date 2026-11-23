package eu.nordtal.season.smp.config;

import eu.nordtal.season.database.inbox.AreaShape;
import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.settings.SettingRefused;
import eu.nordtal.season.smp.area.Polygons;
import java.util.Collection;
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
}
