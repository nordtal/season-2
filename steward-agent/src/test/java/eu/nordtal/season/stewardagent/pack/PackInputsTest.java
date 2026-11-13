package eu.nordtal.season.stewardagent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.settings.MemorySettingStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The prizes come from an admin, else from what smp published, else from the release.
 * The pack sees item and amount.
 */
class PackInputsTest {

    @TempDir
    Path dir;

    private final MemorySettingStore settings = new MemorySettingStore();

    private Path shipped() throws IOException {
        return Files.writeString(dir.resolve("wheel-prizes.json"), "[{\"item\":\"stone\",\"amount\":64}]");
    }

    @Test
    void anAdminsPrizesComeFirstAndKeepOnlyWhatThePackDraws() throws IOException {
        settings.publish(
                "smp", "wheel", "{}", "{\"wheel-prizes\":[{\"item\":\"dirt\",\"amount\":3}]}", List.of(), true);
        settings.set("smp", "wheel", "wheel-prizes", "[{\"item\":\"diamond\",\"weight\":5,\"data\":\"x\"}]");

        assertEquals("[{\"item\":\"diamond\",\"amount\":1}]", PackInputs.prizes(settings, shipped()));
    }

    @Test
    void withoutAnAdminsPrizesSmpsPublishedDefaultsCount() throws IOException {
        settings.publish(
                "smp", "wheel", "{}", "{\"wheel-prizes\":[{\"item\":\"dirt\",\"amount\":3}]}", List.of(), true);

        assertEquals("[{\"item\":\"dirt\",\"amount\":3}]", PackInputs.prizes(settings, shipped()));
    }

    @Test
    void anSmpThatNeverStartedGetsTheReleasesPrizes() throws IOException {
        assertEquals("[{\"item\":\"stone\",\"amount\":64}]", PackInputs.prizes(settings, shipped()));
    }

    @Test
    void thePrizesAsTheDatabaseSpellsThemDrawTheSamePack() {
        final PackInputs built = new PackInputs("[{\"item\":\"diamond\",\"amount\":2}]", "{}", Map.of("de", "Drehen"));
        final PackInputs stored =
                new PackInputs("[{\"item\": \"diamond\", \"amount\": 2}]", "", Map.of("de", "Drehen"));
        final PackInputs reordered =
                new PackInputs("[{\"amount\": 2, \"item\": \"diamond\"}]", "", Map.of("de", "Drehen"));

        assertTrue(stored.drawsSame(built));
        assertTrue(reordered.drawsSame(built));
        assertFalse(
                built.drawsSame(new PackInputs("[{\"item\":\"diamond\",\"amount\":3}]", "{}", Map.of("de", "Drehen"))));
    }
}
