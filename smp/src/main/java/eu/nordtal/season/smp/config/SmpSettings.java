package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Checks;
import eu.nordtal.season.smp.board.BoardFrame;

/**
 * Every rule about what a valid value of smp's settings is.
 *
 * The track and sounds groups are separate from {@code config} so a reload can re-read them.
 */
public final class SmpSettings {

    private SmpSettings() {}

    /**
     * Refuses a track whose structure is broken; its names and stored progress are checked where it is taken.
     *
     * @throws IllegalArgumentException listing every problem
     */
    public static void checkMilestones(final MilestonesSpec config) {
        final Milestones.Result result = Milestones.read(config);
        if (!result.problems().isEmpty()) {
            throw new IllegalArgumentException("the milestone track is not usable:\n" + result.describe());
        }
    }

    /**
     * Refuses a {@code config} group the season cannot run on.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void check(final SmpSpec config) {
        // Whether the world exists is checked at enable, after {@code Worlds#bootstrap} creates it.
        Checks.requireText("world-nordtal", config.worldNordtal());
        Checks.requireText("first-join-spawn: world", config.firstJoinSpawn().world());
        // {@code AdminWatch} floors this at one second.
        Checks.requirePositive("nether-border-diameter", config.netherBorderDiameter());
        Checks.requirePositive("end-border-diameter", config.endBorderDiameter());
        Checks.requirePositive("border-expansion-blocks-per-second", config.borderExpansionBlocksPerSecond());

        if (config.deathPenalty() < 0 || config.deathPenaltyListed() < 0) {
            throw new IllegalArgumentException(
                    "death penalties are configured as positive numbers and subtracted at the point "
                            + "of use, so neither may be negative");
        }
        if (config.duelStake() < 0) {
            throw new IllegalArgumentException("duel-stake must not be negative");
        }
        if (config.graveMaxAgeHours() < 0) {
            // Zero means "never decays", so it cannot double as the error case.
            throw new IllegalArgumentException(
                    "grave-max-age-hours must not be negative. 0 is how decay is turned off; a "
                            + "negative number is not a shorter way of saying that");
        }
        Checks.requirePositive("concurrent-duel-limit", config.concurrentDuelLimit());

        validateAdvancementAwards(config);
        validateWheelPrizes(config);
        validateBoards(config);
        validateSpawnRegions(config);
    }

    private static void validateAdvancementAwards(final SmpSpec config) {
        for (final AdvancementAwardSpec award : config.advancementAwards()) {
            if (award.advancement() == null || award.advancement().isBlank()) {
                throw new IllegalArgumentException("advancement-awards: every entry needs an advancement");
            }
            if (award.aura() < 2 || award.aura() > 10) {
                throw new IllegalArgumentException(
                        "advancement-awards: '" + award.advancement() + "' pays " + award.aura()
                                + " aura; the band is 2-10, so that one advancement cannot outweigh "
                                + "a whole objective");
            }
        }
    }

    private static void validateWheelPrizes(final SmpSpec config) {
        if (config.wheelPrizes() == null || config.wheelPrizes().isEmpty()) {
            throw new IllegalArgumentException(
                    "wheel-prizes must not be empty; the wheel has to have " + "something to land on");
        }
        for (final WheelPrizeSpec prize : config.wheelPrizes()) {
            Checks.requireText("wheel-prizes: item", prize.item());
            Checks.requirePositive("wheel-prizes: weight for '" + prize.item() + "'", prize.weight());
            Checks.requirePositive("wheel-prizes: amount for '" + prize.item() + "'", prize.amount());
        }
    }

    private static void validateBoards(final SmpSpec config) {
        for (final BoardSpec board : config.boards()) {
            Checks.requireText("boards: world", board.world());
            if (board.width() < BoardFrame.MIN_WIDTH || board.width() > BoardFrame.MAX_WIDTH) {
                throw new IllegalArgumentException(
                        "boards: '" + board.kind() + "' is " + board.width() + " pixels wide; the "
                                + "frame's shifts reach " + BoardFrame.MIN_WIDTH + " to "
                                + BoardFrame.MAX_WIDTH + ", and a width outside that would throw on "
                                + "the first render rather than here");
            }
        }
    }

    private static void validateSpawnRegions(final SmpSpec config) {
        for (final SpawnRegionSpec region : config.spawnRegions()) {
            Checks.requireText("spawn-regions: world", region.world());
            if (region.maxX() < region.minX() || region.maxY() < region.minY() || region.maxZ() < region.minZ()) {
                throw new IllegalArgumentException("spawn-regions: the box in '" + region.world()
                        + "' has a max corner that is " + "not above its min corner");
            }
        }
    }
}
