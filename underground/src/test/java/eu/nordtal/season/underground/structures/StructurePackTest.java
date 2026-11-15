package eu.nordtal.season.underground.structures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.RepositoryRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The data pack of the smp turns off the game's sets and holds the sets the map's starts use. */
class StructurePackTest {

    private static final Path PACK = RepositoryRoot.path().resolve("deploy/minecraft/datapacks/nordtal");

    private static final List<String> SETS =
            List.of(Planner.VILLAGES, Planner.OUTPOSTS, Planner.MONUMENTS, Planner.STRONGHOLDS);

    private static final Pattern FREQUENCY = Pattern.compile("\"frequency\"\\s*:\\s*([0-9.]+)");

    private static final Pattern STRUCTURE = Pattern.compile("\"structure\"\\s*:\\s*\"([^\"]+)\"");

    @Test
    void noSetOfThePackPlacesAnythingNew() throws IOException {
        final List<String> placing = new ArrayList<>();
        for (final String namespace : List.of("minecraft", "nordtal")) {
            for (final String set : SETS) {
                final Matcher frequency = FREQUENCY.matcher(read(namespace, set));
                if (!frequency.find() || Double.parseDouble(frequency.group(1)) != 0.0) {
                    placing.add(namespace + ":" + set);
                }
            }
        }

        assertEquals(List.of(), placing);
    }

    @Test
    void onlyTheMapsSetsHoldTheGamesStructures() throws IOException {
        final Map<String, List<String>> wanted = Map.of(
                Planner.VILLAGES,
                List.of(
                        "minecraft:village_desert",
                        "minecraft:village_plains",
                        "minecraft:village_savanna",
                        "minecraft:village_snowy",
                        "minecraft:village_taiga"),
                Planner.OUTPOSTS,
                List.of("minecraft:pillager_outpost"),
                Planner.MONUMENTS,
                List.of("minecraft:monument"),
                Planner.STRONGHOLDS,
                List.of("minecraft:stronghold"));
        for (final String set : SETS) {
            assertEquals(wanted.get(set), structures(read("nordtal", set)), set);
            // /locate searches every set of a structure ring by ring, so a wider set of the game would win.
            assertEquals(List.of(), structures(read("minecraft", set)), set);
        }
    }

    @Test
    void everyMapSetIsASpreadTheCommandCanPlanOn() throws IOException {
        for (final String set : SETS) {
            assertEquals(8, Spread.read(file("nordtal", set)).spacing(), set);
        }
    }

    private static Path file(final String namespace, final String set) {
        return PACK.resolve("data/" + namespace + "/worldgen/structure_set/" + set + ".json");
    }

    private static String read(final String namespace, final String set) throws IOException {
        return Files.readString(file(namespace, set), StandardCharsets.UTF_8);
    }

    private static List<String> structures(final String json) {
        final List<String> names = new ArrayList<>();
        final Matcher matcher = STRUCTURE.matcher(json);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names.stream().sorted().toList();
    }
}
