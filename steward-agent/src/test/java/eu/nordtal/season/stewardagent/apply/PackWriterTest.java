package eu.nordtal.season.stewardagent.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.stewardagent.plan.PackState;
import org.junit.jupiter.api.Test;

/** The two values of the proxy's pack group Steward owns, and the ones it must not disturb. */
class PackWriterTest {

    private static final String URL =
            "https://github.com/nordtal/season-2/releases/download/v0.2.0/nordtal-resource-pack-0.2.0.zip";
    private static final String SHA1 = "6f1ed002ab5595859014ebf0951522d9d0f2ee34";

    private final MemorySettingStore store = new MemorySettingStore();

    @Test
    void bothValuesAreStoredAsSteward() {
        assertTrue(PackWriter.write(store, URL, SHA1));

        assertEquals(new PackState(URL, SHA1), PackState.read(store));
        assertEquals(Actor.STEWARD, store.actorAt("proxy", "pack", "sha1"));
    }

    @Test
    void aProxyThatNeverStartedStillGetsItsPack() {
        // The proxy publishes its groups on its first start, which needs the plugins this same run installs.
        assertTrue(store.group("proxy", "pack").isEmpty());

        assertTrue(PackWriter.write(store, URL, SHA1));

        assertEquals(SHA1, PackState.read(store).sha1());
    }

    @Test
    void aGroupThatAlreadySaysThisIsNotWrittenAgain() {
        PackWriter.write(store, URL, SHA1);
        store.set("proxy", "pack", "force", false);

        assertFalse(PackWriter.write(store, URL, SHA1));
    }

    @Test
    void theAdminsOtherValuesSurvive() {
        store.set("proxy", "pack", "force", false).set("proxy", "pack", "sha1", "old");

        PackWriter.write(store, URL, SHA1);

        assertEquals(
                "false",
                store.overrides(java.util.List.of("proxy")).stream()
                        .filter(value -> value.path().equals("force"))
                        .findFirst()
                        .orElseThrow()
                        .value());
    }
}
