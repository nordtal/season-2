package eu.nordtal.s2.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.settings.Group;
import eu.nordtal.s2.settings.MemorySettingStore;
import io.javalin.http.BadRequestResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsDocumentTest {

    private static final Group<ExampleGroupSpec> EXAMPLE = Group.of("example", ExampleGroupSpec.class);

    private final MemorySettingStore store = new MemorySettingStore();

    private SettingsDocument document() throws Exception {
        store.settings("smp").load(EXAMPLE);
        final SettingStore.Group group = store.group("smp", "example").orElseThrow();
        return SettingsDocument.of(group, store.overrides(List.of("smp")));
    }

    private static SettingsDocument.Entry entry(final SettingsDocument document, final String path) {
        return document.document(null).entries().stream()
                .filter(entry -> path.equals(entry.path()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theFormShowsTheStoredValueOverTheDefault() throws Exception {
        store.set("smp", "example", "motd", "Welcome");

        final SettingsDocument document = document();

        assertEquals("Welcome", entry(document, "motd").value());
        assertEquals("20", entry(document, "max-players").value());
        assertEquals(List.of("msg"), entry(document, "allowlist").items());
    }

    @Test
    void aSecretIsNeitherShownNorEditable() throws Exception {
        final SettingsDocument.Entry token = entry(document(), "token");

        assertNull(token.value());
        assertFalse(token.editable());
    }

    @Test
    void aValueBackAtItsDefaultRemovesItsRow() throws Exception {
        final JsonObject changes = new JsonObject();
        changes.addProperty("max-players", "20");
        changes.addProperty("motd", "Welcome");

        final Map<String, String> rows = document().rowsFor(changes);

        assertNull(rows.get("max-players"));
        assertTrue(rows.containsKey("max-players"));
        assertEquals("\"Welcome\"", rows.get("motd"));
    }

    @Test
    void aNumberIsStoredAsANumberAndListsAsLists() throws Exception {
        final JsonObject changes = new JsonObject();
        changes.addProperty("max-players", "50");
        final JsonArray allowlist = new JsonArray();
        allowlist.add("msg");
        allowlist.add("tell");
        changes.add("allowlist", allowlist);

        final Map<String, String> rows = document().rowsFor(changes);

        assertEquals("50", rows.get("max-players"));
        assertEquals("[\"msg\",\"tell\"]", rows.get("allowlist"));
    }

    @Test
    void aWrongTypeAnUnknownPathAndASecretAreRefused() throws Exception {
        final SettingsDocument document = document();
        for (final String path : List.of("max-players", "nowhere", "token")) {
            final JsonObject changes = new JsonObject();
            changes.addProperty(path, "many");
            assertThrows(BadRequestResponse.class, () -> document.rowsFor(changes), path);
        }
    }

    @Test
    void aSaveOnAStaleRevisionWritesNothing() throws Exception {
        final String read = document().document(null).revision();
        store.set("smp", "example", "motd", "changed in between");
        final String now = document().document(null).revision();

        final boolean written = store.change(
                "smp",
                "example",
                Map.of("motd", "\"mine\""),
                Actor.STEWARD,
                held -> SettingsDocument.revisionOf(held).equals(read));

        assertNotEquals(read, now);
        assertFalse(written);
        assertEquals("changed in between", entry(document(), "motd").value());
    }
}
