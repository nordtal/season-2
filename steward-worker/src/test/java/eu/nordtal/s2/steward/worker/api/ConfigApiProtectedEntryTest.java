package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SettingKind;
import eu.nordtal.jcore.config.schema.SettingType;
import eu.nordtal.s2.steward.worker.configfile.ConfigFiles;
import eu.nordtal.s2.steward.worker.configfile.ConfigLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one step between {@code ConfigFiles} and the browser (steward/74).
 *
 * <h2>Why this class exists at all</h2>
 * Everything else about {@code @Protected} was already tested, at both ends: jcore builds the
 * schema node, {@code ConfigFiles} carries it onto the entry and refuses the removal, and
 * {@code repeatable-cards.tsx} greys out the section it names. All of it green - and a live read of
 * {@code /api/config/discord-bot/access.yml} on the running worker, 2026-09-17, still answered
 * without a {@code protectedEntry}.
 *
 * <p>{@link ConfigApi#document} writes its answer key by key into a {@link java.util.LinkedHashMap},
 * and a key nobody adds is simply absent - nothing throws, nothing warns, and every suite on either
 * side of it stays green. The refusal still worked, so nothing was unsafe; what was dead was the
 * half an operator sees, which is the half that stops somebody trying. A hand-written map is a
 * place where "I added the field" and "the field is sent" are two different facts, so this holds the
 * second one.</p>
 */
class ConfigApiProtectedEntryTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path directory;

    @Test
    @DisplayName("the JSON the browser is sent carries protectedEntry, or the grey-out never happens")
    void theDocumentCarriesTheProtectedEntry() throws IOException {
        writeFile(
                "languages:\n- tag: en\n- tag: de\n",
                Map.of("languages", sections("Languages", new SchemaNode.ProtectedEntry("tag", "en"))));

        assertEquals(
                Map.of("field", "tag", "value", "en"),
                entry("languages").get("protectedEntry"),
                "api.ts declares ConfigEntry.protectedEntry and repeatable-cards.tsx greys out the"
                        + " section it names - neither can do anything if the worker never sends it");
    }

    @Test
    @DisplayName("an entry with no protected section sends no protectedEntry key at all")
    void theDocumentOmitsTheKeyWhenThereIsNoRule() throws IOException {
        writeFile("tiers:\n- tag: bronze\n", Map.of("tiers", sections("Tiers", null)));

        assertTrue(
                !entry("tiers").containsKey("protectedEntry"),
                "a key sent as null is a rule the interface has to test for twice - absent is the"
                        + " same convention `choices` already uses");
    }

    // --- fixtures ----------------------------------------------------------------------------

    private void writeFile(final String yaml, final Map<String, SchemaNode> children) throws IOException {
        Files.writeString(directory.resolve("service.yml"), yaml);
        final SchemaNode root = new SchemaNode(SettingKind.MAP, "", "", false, false, null, null, children, null);
        Files.writeString(directory.resolve("service.schema.json"), GSON.toJson(root));
    }

    private static SchemaNode sections(final String label, final SchemaNode.ProtectedEntry protectedEntry) {
        final SchemaNode tag =
                new SchemaNode(SettingKind.SCALAR, "Tag", "", false, false, SettingType.STRING, null, Map.of(), null);
        return new SchemaNode(
                SettingKind.LIST, label, "", false, false, null, null, Map.of("tag", tag), protectedEntry);
    }

    /** The row for {@code path}, out of the document {@code /api/config/<file>} would answer with. */
    private Map<String, Object> entry(final String path) throws IOException {
        final ConfigLocation location = ConfigFiles.discover(directory).stream()
                .filter(candidate -> candidate.name().equals("service.yml"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("service.yml was not discovered"));
        final Map<String, Object> document =
                ConfigApi.document(location, ConfigFiles.read(directory.resolve("service.yml")));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> entries = (List<Map<String, Object>>) document.get("entries");
        return entries.stream()
                .filter(row -> path.equals(row.get("path")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry " + path + " in " + entries));
    }
}
