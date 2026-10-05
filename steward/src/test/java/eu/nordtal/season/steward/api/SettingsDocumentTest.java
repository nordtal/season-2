package eu.nordtal.season.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
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
    void aValueNamingAnItemSaysSoAndAPlainOneNothing() throws Exception {
        final SettingsDocument document = document();

        assertEquals(
                new SettingsDocument.Reference(Refers.To.ITEM, null, false),
                entry(document, "prizes").refers());
        assertNull(entry(document, "motd").refers());
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
    void aWrongTypeAnUnknownPathAndASecretAreRefusedInTheBundlesWords() throws Exception {
        final SettingsDocument document = document();
        final StewardTexts.Steward.Answer answer = StewardTexts.TEXTS.steward().answer();
        final Map<String, MessageRef> refusals = Map.of(
                "max-players", answer.notOfType("max-players", StewardTexts.Expected.INTEGER, "many"),
                "nowhere", answer.noSetting("example", "nowhere"),
                "token", answer.secretSetting("token"));
        for (final Map.Entry<String, MessageRef> refusal : refusals.entrySet()) {
            final JsonObject changes = new JsonObject();
            changes.addProperty(refusal.getKey(), "many");
            final RequestRefused refused =
                    assertThrows(RequestRefused.class, () -> document.rowsFor(changes), refusal.getKey());
            assertEquals(refusal.getValue(), refused.why(), refusal.getKey());
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
