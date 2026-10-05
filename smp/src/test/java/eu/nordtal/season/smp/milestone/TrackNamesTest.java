package eu.nordtal.season.smp.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.config.Milestones;
import eu.nordtal.season.smp.config.MilestonesSpec;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/** A track naming what the server does not have is refused, with the name in the reason. */
class TrackNamesTest {

    private static final Set<NamespacedKey> ADVANCEMENTS = Set.of(
            NamespacedKey.minecraft("story/iron_tools"),
            NamespacedKey.minecraft("story/mine_diamond"),
            NamespacedKey.minecraft("story/form_obsidian"),
            NamespacedKey.minecraft("nether/obtain_blaze_rod"),
            NamespacedKey.minecraft("adventure/hero_of_the_village"),
            NamespacedKey.minecraft("nether/netherite_armor"));

    /** A server on which water is no item, a diamond no block, and only the default track's advancements exist. */
    private static final TrackNames.Server SERVER = new TrackNames.Server() {
        @Override
        public boolean isItem(final Material material) {
            return material != Material.WATER;
        }

        @Override
        public boolean isBlock(final Material material) {
            return material != Material.DIAMOND;
        }

        @Override
        public boolean hasAdvancement(final NamespacedKey key) {
            return ADVANCEMENTS.contains(key);
        }
    };

    @Test
    void theDefaultTrackNamesOnlyWhatExists() {
        final MilestonesSpec defaults = new MilestonesSpec() {};
        final MilestoneTrack track =
                Objects.requireNonNull(Milestones.read(defaults).track());

        assertEquals(List.of(), TrackNames.validate(track, SERVER));
    }

    @Test
    void anUnknownItemIsRefused() {
        assertRefused(handIn("OAK_LOGS"), "'OAK_LOGS'");
    }

    @Test
    void aMaterialNoPlayerCanCarryIsRefused() {
        assertRefused(handIn("WATER"), "'WATER'");
    }

    @Test
    void anUnknownStatisticIsRefused() {
        assertRefused(statistic("MINE_BLOCKS", List.of("COAL_ORE")), "'MINE_BLOCKS'");
    }

    @Test
    void anUnknownEntityIsRefused() {
        assertRefused(statistic("KILL_ENTITY", List.of("ZOMBIE", "ZOMBIES")), "'ZOMBIES'");
    }

    @Test
    void aSubjectOfTheWrongKindIsRefused() {
        assertRefused(statistic("MINE_BLOCK", List.of("DIAMOND")), "'DIAMOND'");
        assertRefused(statistic("KILL_ENTITY", List.of("COAL_ORE")), "'COAL_ORE'");
    }

    @Test
    void aStatisticKeptPerSubjectNeedsOne() {
        assertRefused(statistic("MINE_BLOCK", List.of()), "MINE_BLOCK");
    }

    @Test
    void aStatisticWithoutSubjectsTakesNone() {
        assertRefused(statistic("JUMP", List.of("STONE")), "JUMP");
    }

    @Test
    void anUnknownAdvancementIsRefused() {
        assertRefused(advancement("minecraft:story/iron_tool"), "'minecraft:story/iron_tool'");
        assertRefused(advancement("Story/Iron_Tools"), "'Story/Iron_Tools'");
    }

    @Test
    void anAdvancementWithoutItsNamespaceIsTheMinecraftOne() {
        assertEquals(List.of(), TrackNames.validate(track(advancement("story/iron_tools")), SERVER));
    }

    private static void assertRefused(final Objective objective, final String named) {
        final List<TrackValidation.Problem> problems = TrackNames.validate(track(objective), SERVER);

        assertEquals(1, problems.size(), "one problem for " + named + ", got " + problems);
        final TrackValidation.Problem problem = problems.getFirst();
        assertEquals("foothold", problem.milestoneKey());
        assertEquals("under-test", problem.objectiveKey());
        assertTrue(problem.message().contains(named), problem.message() + " does not name " + named);
    }

    private static MilestoneTrack track(final Objective objective) {
        return new MilestoneTrack(List.of(new Milestone("foothold", Unlock.BORDER, 99, 30, false, List.of(objective))));
    }

    private static Objective handIn(final String item) {
        return new Objective(
                "under-test", ObjectiveType.HAND_IN, "gathering", 64, List.of("OAK_LOG", item), "", List.of(), "");
    }

    private static Objective statistic(final String statistic, final List<String> subjects) {
        return new Objective("under-test", ObjectiveType.STATISTIC, "mining", 64, List.of(), statistic, subjects, "");
    }

    private static Objective advancement(final String advancement) {
        return new Objective(
                "under-test", ObjectiveType.ADVANCEMENT, "participation", 10, List.of(), "", List.of(), advancement);
    }
}
