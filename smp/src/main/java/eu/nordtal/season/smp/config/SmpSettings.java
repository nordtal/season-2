package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Checks;
import eu.nordtal.season.smp.board.BoardFrame;

public final class SmpSettings {

    private SmpSettings() {}

    /** Throws {@link IllegalArgumentException} for a broken structure. It checks neither names nor stored progress. */
    public static void checkMilestones(final MilestonesSpec config) {
        final Milestones.Result result = Milestones.read(config);
        if (!result.problems().isEmpty()) {
            throw new IllegalArgumentException("the milestone track is not usable:\n" + result.describe());
        }
    }

    public static void checkWorlds(final WorldsSpec config) {
        Checks.requireText("world-nordtal", config.worldNordtal());
    }

    public static void checkBorder(final BorderSpec config) {
        Checks.requirePositive("nether-border-diameter", config.netherBorderDiameter());
        Checks.requirePositive("end-border-diameter", config.endBorderDiameter());
        Checks.requirePositive("border-expansion-blocks-per-second", config.borderExpansionBlocksPerSecond());
    }

    public static void checkDeaths(final DeathsSpec config) {
        if (config.deathPenalty() < 0 || config.deathPenaltyListed() < 0) {
            throw new IllegalArgumentException(
                    "death penalties are configured as positive numbers and subtracted at the point "
                            + "of use, so neither may be negative");
        }
        if (config.graveMaxAgeHours() < 0) {
            throw new IllegalArgumentException(
                    "grave-max-age-hours must not be negative. 0 is how decay is turned off; a "
                            + "negative number is not a shorter way of saying that");
        }
    }

    public static void checkDuels(final DuelsSpec config) {
        if (config.duelStake() < 0) {
            throw new IllegalArgumentException("duel-stake must not be negative");
        }
        Checks.requirePositive("concurrent-duel-limit", config.concurrentDuelLimit());
        Checks.requirePositive("duel-arena-spacing", config.duelArenaSpacing());
    }

    public static void checkAdvancementAwards(final AdvancementAwardsSpec config) {
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
            if (award.spins() < 0) {
                throw new IllegalArgumentException(
                        "advancement-awards: '" + award.advancement() + "' grants " + award.spins() + " spins");
            }
        }
    }

    public static void checkWheel(final WheelSpec config) {
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

    public static void checkBoards(final BoardsSpec config) {
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

    public static void checkSpawn(final SpawnSpec config) {
        Checks.requireText("first-join-spawn: world", config.firstJoinSpawn().world());
        for (final SpawnRegionSpec region : config.spawnRegions()) {
            Checks.requireText("spawn-regions: world", region.world());
            if (region.maxX() < region.minX() || region.maxY() < region.minY() || region.maxZ() < region.minZ()) {
                throw new IllegalArgumentException("spawn-regions: the box in '" + region.world()
                        + "' has a max corner that is " + "not above its min corner");
            }
        }
    }
}
