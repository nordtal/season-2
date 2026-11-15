package eu.nordtal.season.smp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The structure pack the plugin brings turns off the game's new places and holds the sets the map's starts use. */
class SmpBootstrapTest {

    private static final List<String> SETS = List.of("villages", "pillager_outposts", "ocean_monuments", "strongholds");

    @Test
    void noSetOfThePackPlacesAnythingNew() throws IOException {
        final Path pack = Path.of(SmpBootstrap.structures());
        final List<String> placing = new ArrayList<>();
        for (final String namespace : List.of("minecraft", "nordtal")) {
            for (final String set : SETS) {
                final JsonObject placement = read(pack.resolve(
                                "data/" + namespace + "/worldgen/structure_set/" + set + ".json"))
                        .getAsJsonObject("placement");
                if (placement.get("frequency").getAsDouble() != 0.0) {
                    placing.add(namespace + ":" + set);
                }
            }
        }

        assertEquals(List.of(), placing);
    }

    @Test
    void theMapsSetsHoldTheStructuresOfTheGamesSets() throws IOException {
        final Path pack = Path.of(SmpBootstrap.structures());
        for (final String set : SETS) {
            assertEquals(
                    structures(read(pack.resolve("data/minecraft/worldgen/structure_set/" + set + ".json"))),
                    structures(read(pack.resolve("data/nordtal/worldgen/structure_set/" + set + ".json"))),
                    set);
        }
    }

    private static JsonObject read(final Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static List<String> structures(final JsonObject set) {
        final List<String> names = new ArrayList<>();
        set.getAsJsonArray("structures")
                .forEach(entry ->
                        names.add(entry.getAsJsonObject().get("structure").getAsString()));
        return names.stream().sorted().toList();
    }
}
