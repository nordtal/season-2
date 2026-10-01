package eu.nordtal.s2.steward.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.settings.MemorySettingStore;
import org.junit.jupiter.api.Test;

/** Reading the proxy's pack out of its stored settings. */
class PackStateTest {

    private static final String URL =
            "https://github.com/nordtal/season-2/releases/download/v0.1.0/nordtal-resource-pack-0.1.0.zip";

    private final MemorySettingStore store = new MemorySettingStore();

    @Test
    void aHashMadeOnlyOfDigitsIsTextNotANumber() {
        store.set("proxy", "pack", "sha1", "0000000000000000000000000000000000000000");

        assertEquals(
                "0000000000000000000000000000000000000000",
                PackState.read(store).sha1());
    }

    @Test
    void theOrdinaryCaseUrlAndSha1ComeBackAsTheyAreStored() {
        store.set("proxy", "pack", "url", URL).set("proxy", "pack", "sha1", "6f1ed002ab5595859014ebf0951522d9d0f2ee34");

        final PackState state = PackState.read(store);

        assertTrue(state.present());
        assertEquals("6f1ed002ab5595859014ebf0951522d9d0f2ee34", state.sha1());
        assertEquals(URL, state.url());
    }

    @Test
    void nothingStoredIsAbsent() {
        final PackState state = PackState.read(store);

        assertFalse(state.present());
        assertNull(state.url());
    }

    @Test
    void anEmptyValueIsEmptyNotTheTextNull() {
        store.set("proxy", "pack", "url", "").set("proxy", "pack", "sha1", " ");

        final PackState state = PackState.read(store);

        assertFalse(state.present());
        assertNull(state.url());
        assertNull(state.sha1());
    }

    @Test
    void anotherGroupOrServiceIsNotThePack() {
        store.set("proxy", "network", "sha1", "6f1ed002ab5595859014ebf0951522d9d0f2ee34")
                .set("limbo", "pack", "sha1", "6f1ed002ab5595859014ebf0951522d9d0f2ee34");

        assertFalse(PackState.read(store).present());
    }
}
