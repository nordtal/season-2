package eu.nordtal.season.steward.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.spec.schema.SettingType;
import eu.nordtal.season.steward.settings.SettingsDocument;
import eu.nordtal.season.steward.texts.RequestRefused;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** A plugin file's form and its save, which writes the changed values and keeps every other byte. */
class PluginYamlTest {

    private static final String YAML = """
            # CoreProtect, configured by hand

            # Whether a MySQL server holds the data.
            use-mysql: false   # off
            table-prefix: co_
            port: 3306
            ratio: 1.50
            empty:
            worlds:
              - world
              - world_nether
            ignored: [a, b]
            database:
              # The host name.
              host: "localhost"
            """;

    private static JsonObject changes(final String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void theFormHoldsEveryValueWithItsTypeAndTheCommentAboveIt() {
        final List<SettingsDocument.Entry> entries = PluginYaml.entries("config.yml", YAML);

        assertNotNull(entries);
        final Map<String, SettingsDocument.Entry> byPath =
                entries.stream().collect(Collectors.toMap(SettingsDocument.Entry::path, entry -> entry));
        assertEquals(
                List.of(
                        "use-mysql",
                        "table-prefix",
                        "port",
                        "ratio",
                        "empty",
                        "worlds",
                        "ignored",
                        "database",
                        "database.host"),
                entries.stream().map(SettingsDocument.Entry::path).toList());
        assertEquals(SettingType.BOOLEAN, byPath.get("use-mysql").type());
        assertEquals("false", byPath.get("use-mysql").value());
        assertEquals(
                "Whether a MySQL server holds the data.",
                byPath.get("use-mysql").explanation());
        assertEquals(SettingType.INTEGER, byPath.get("port").type());
        assertEquals(SettingsDocument.Shape.LIST, byPath.get("worlds").kind());
        assertEquals(List.of("world", "world_nether"), byPath.get("worlds").items());
        assertEquals(SettingsDocument.Shape.MAP, byPath.get("database").kind());
        assertEquals("localhost", byPath.get("database.host").value());
        assertEquals("The host name.", byPath.get("database.host").explanation());
    }

    @Test
    void aSaveRewritesOnlyTheChangedValues() {
        final String written = PluginYaml.apply("config.yml", YAML, changes("""
                        {"use-mysql": "true", "port": "3307", "empty": "x: y", "worlds": ["one", "it's"],
                         "ignored": [], "database.host": "db"}
                        """));

        assertEquals("""
                # CoreProtect, configured by hand

                # Whether a MySQL server holds the data.
                use-mysql: true   # off
                table-prefix: co_
                port: 3307
                ratio: 1.50
                empty: 'x: y'
                worlds:
                  - one
                  - it's
                ignored: []
                database:
                  # The host name.
                  host: db
                """, written);
    }

    @Test
    void aBlockListEmptiedIsWrittenAsBrackets() {
        assertEquals(
                "worlds: []\nnext: 1\r\n",
                PluginYaml.apply("a.yaml", "worlds:\n- a\n- b\nnext: 1\r\n", changes("{\"worlds\": []}")));
    }

    @Test
    void aJsonFileGetsJsonStrings() {
        final String json = "{\n  \"name\": \"x\",\n  \"limit\": 5,\n  \"tags\": [\"a\"]\n}\n";

        assertEquals(
                "{\n  \"name\": \"say \\\"hi\\\"\",\n  \"limit\": 6,\n  \"tags\": [\"b\", \"c\"]\n}\n",
                PluginYaml.apply(
                        "data.json",
                        json,
                        changes("{\"name\": \"say \\\"hi\\\"\", \"limit\": \"6\", \"tags\": [\"b\", \"c\"]}")));
    }

    @Test
    void aValueOfAnotherTypeOrAnUnknownPathIsRefused() {
        assertThrows(RequestRefused.class, () -> PluginYaml.apply("config.yml", YAML, changes("{\"port\": \"many\"}")));
        assertThrows(RequestRefused.class, () -> PluginYaml.apply("config.yml", YAML, changes("{\"nothing\": \"1\"}")));
    }

    @Test
    void aFileWhoseValuesCannotBeRewrittenInPlaceOpensAsText() {
        assertNull(PluginYaml.entries("messages.yml", "motd: |\n  Hello\n  there\n"));
        assertNull(PluginYaml.entries("kits.yml", "kits:\n  - name: a\n"));
        assertNull(PluginYaml.entries("a.yml", "base: &b 1\nother: *b\n"));
        assertNull(PluginYaml.entries("a.yml", "list:\n  - a\n  # gone\n  - b\n"));
        assertNull(PluginYaml.entries("a.yml", "- just\n- a list\n"));
        assertNull(PluginYaml.entries("server.properties", "a=b\n"));
        assertNull(PluginYaml.entries("broken.yml", "a: [\n"));
    }
}
