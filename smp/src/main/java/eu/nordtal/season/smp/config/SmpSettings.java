package eu.nordtal.season.smp.config;

import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.packrendering.board.BoardFrame;
import eu.nordtal.season.settings.Checks;
import eu.nordtal.season.settings.SettingRefused;

public final class SmpSettings {

    private SmpSettings() {}

    /** Throws {@link SettingRefused} for a broken structure. It checks neither names nor stored progress. */
    public static void checkMilestones(final MilestonesSpec config) {
        final Milestones.Result result = Milestones.read(config);
        if (!result.problems().isEmpty()) {
            throw new SettingRefused(
                    "milestones", SettingRule.TRACK, "the milestone track is not usable:\n" + result.describe());
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
        // Death penalties are positive numbers, subtracted at the point of use.
        Checks.requireNotNegative("death-penalty", config.deathPenalty());
        Checks.requireNotNegative("death-penalty-listed", config.deathPenaltyListed());
        // 0 is how decay is turned off; a negative number is not a shorter way of saying that.
        Checks.requireNotNegative("grave-max-age-hours", config.graveMaxAgeHours());
    }

    private static final int KIT_NAME_LIMIT = 64;

    private static final int AWARD_LEAST = 2;

    private static final int AWARD_MOST = 10;

    /** The negotiation window has five kit rows. */
    private static final int KIT_LIMIT = 5;

    public static void checkDuels(final DuelsSpec config) {
        Checks.requireNotNegative("duel-stake", config.duelStake());
        Checks.requireText("negotiation-area.world", config.negotiationArea().world());
        Checks.requireText("arena.world", config.arena().world());
        Checks.requireText(
                "fighter-start-first.world", config.fighterStartFirst().world());
        Checks.requireText(
                "fighter-start-second.world", config.fighterStartSecond().world());
        Checks.requireText("exit.world", config.exit().world());
        if (config.duelKits().isEmpty()) {
            throw new SettingRefused(
                    "duel-kits",
                    SettingRule.REQUIRED,
                    "duel-kits: two players can only agree on a kit if there is one");
        }
        if (config.duelKits().size() > KIT_LIMIT) {
            throw new SettingRefused(
                    "duel-kits",
                    SettingRule.MOST_ENTRIES,
                    KIT_LIMIT,
                    "duel-kits: the negotiation window shows at most " + KIT_LIMIT + " kits");
        }
        for (final DuelKitSpec kit : config.duelKits()) {
            Checks.requireText("duel-kits", kit.name());
            if (kit.name().length() > KIT_NAME_LIMIT) {
                // The aura history stores the kit by name in a column of this width.
                throw new SettingRefused(
                        "duel-kits",
                        SettingRule.LONGEST,
                        KIT_NAME_LIMIT,
                        "duel-kits: the name '" + kit.name() + "' is longer than " + KIT_NAME_LIMIT + " characters");
            }
        }
    }

    public static void checkAdvancementAwards(final AdvancementAwardsSpec config) {
        for (final AdvancementAwardSpec award : config.advancementAwards()) {
            Checks.requireText("advancement-awards", award.advancement());
            // The band keeps one advancement from outweighing a whole objective.
            Checks.requireBetween("advancement-awards", award.aura(), AWARD_LEAST, AWARD_MOST);
            Checks.requireNotNegative("advancement-awards", award.spins());
        }
    }

    public static void checkWheel(final WheelSpec config) {
        if (config.wheelPrizes() == null || config.wheelPrizes().isEmpty()) {
            throw new SettingRefused(
                    "wheel-prizes",
                    SettingRule.REQUIRED,
                    "wheel-prizes must not be empty; the wheel has to have something to land on");
        }
        for (final WheelPrizeSpec prize : config.wheelPrizes()) {
            Checks.requireText("wheel-prizes", prize.item());
            Checks.requirePositive("wheel-prizes", prize.weight());
            Checks.requirePositive("wheel-prizes", prize.amount());
        }
    }

    public static void checkBoards(final BoardsSpec config) {
        for (final BoardSpec board : config.boards()) {
            Checks.requireText("boards", board.world());
            // The frame's shifts reach this far; a width outside would throw on the first render rather than here.
            Checks.requireBetween("boards", board.width(), BoardFrame.MIN_WIDTH, BoardFrame.MAX_WIDTH);
        }
    }

    public static void checkSpawn(final SpawnSpec config) {
        Checks.requireText("first-join-spawn.world", config.firstJoinSpawn().world());
        for (final SpawnRegionSpec region : config.spawnRegions()) {
            Checks.requireText("spawn-regions", region.world());
            if (region.maxX() < region.minX() || region.maxY() < region.minY() || region.maxZ() < region.minZ()) {
                throw new SettingRefused(
                        "spawn-regions",
                        SettingRule.CORNERS,
                        "spawn-regions: the box in '" + region.world() + "' has a max corner below its min corner");
            }
        }
    }
}
