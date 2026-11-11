package eu.nordtal.season.smp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.RepositoryRoot;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The pack prepares the wheel's prizes from its own list; a prize missing there turns as a "?". */
class WheelPrizeListTest {

    private static final String LIST = "resource-pack/wheel-prizes.json";

    private static final Pattern ENTRY =
            Pattern.compile("\"item\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"amount\"\\s*:\\s*(\\d+)");

    @Test
    void thePackPreparesEveryDefaultPrizeWithItsAmount() {
        final List<String> pack = new ArrayList<>();
        final Matcher entry = ENTRY.matcher(RepositoryRoot.read(LIST));
        while (entry.find()) {
            pack.add(entry.group(1) + " x" + entry.group(2));
        }
        final List<String> server = DefaultSmp.WHEEL_PRIZES.stream()
                .map(prize -> prize.item() + " x" + prize.amount())
                .toList();

        assertEquals(server, pack, LIST + " should list the default wheel prizes in their order and amounts");
    }
}
