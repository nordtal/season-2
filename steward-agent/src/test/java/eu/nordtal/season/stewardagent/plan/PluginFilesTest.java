package eu.nordtal.season.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.Sha256;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.stewardagent.AgentStandIn;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PluginFiles} over a fixture volume with one folder per case, reached through the agent's own routes.
 *
 * The listing is asserted as an exact set: a file too many is a Nordtal setting, a scratch file or a binary that an
 * admin could break.
 */
class PluginFilesTest {

    private static final String ICON = "https://cdn.modrinth.com/data/spark/icon.png";

    @TempDir
    Path scratch;

    private AgentStandIn agent;
    private AgentClient client;
    private Path plugins;

    @BeforeEach
    void start() throws IOException {
        plugins = Files.createDirectories(scratch.resolve("volumes/smp/plugins"));
        final List<AgentWire.Plugin> rows = List.of(
                plugin("SMP", AgentWire.PluginGroup.NORDTAL, "smp", null),
                // A Nordtal jar whose folder is not named after its prefix is still Nordtal's.
                plugin("Proxy", AgentWire.PluginGroup.NORDTAL, "NordtalProxy", null),
                plugin("spark", AgentWire.PluginGroup.PREINSTALLED, "spark", ICON),
                plugin("CoreProtect", AgentWire.PluginGroup.ADDED, "CoreProtect", null),
                plugin("FastAsyncWorldEdit", AgentWire.PluginGroup.ADDED, "FastAsyncWorldEdit", null),
                plugin("No descriptor", AgentWire.PluginGroup.PREINSTALLED, null, null));
        final PluginFiles files = new PluginFiles(
                service -> {
                    if (!service.equals("smp")) {
                        throw new NotFoundResponse(service + " is not a Minecraft service.");
                    }
                    return new AgentWire.Plugins("smp", "paper", "1.21.10", true, rows);
                },
                scratch.resolve("volumes"));
        agent = new AgentStandIn(scratch, 0, config -> {
            config.routes.get(AgentWire.PLUGIN_FILES, files::list);
            config.routes.get(AgentWire.PLUGIN_FILE, files::read);
            config.routes.post(AgentWire.PLUGIN_FILE, files::save);
        });
        client = new AgentClient(agent.client());
    }

    @AfterEach
    void stop() throws IOException {
        agent.close();
    }

    private static AgentWire.Plugin plugin(
            final String name,
            final AgentWire.PluginGroup group,
            final @Nullable String dataFolder,
            final @Nullable String iconUrl) {
        return new AgentWire.Plugin(
                name,
                group,
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                dataFolder,
                null,
                null,
                null,
                null,
                iconUrl,
                null);
    }

    private Path write(final String path, final String text) throws IOException {
        final Path file = plugins.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private void fixture() throws IOException {
        // Nordtal's own folders: by the jar's group, by the known name and the name tags no jar claims.
        write("smp/config.yml", "a: 1\n");
        write("NordtalProxy/config.yml", "a: 1\n");
        write("hunger-games/config.yml", "a: 1\n");
        write("DisplayTags/config.yml", "a: 1\n");

        // A preinstalled plugin with an icon, its scratch folder and a log.
        write("spark/config.json", "{}\n");
        write("spark/tmp/about.txt", "scratch\n");
        write("spark/logs/latest.txt", "log\n");
        write("spark/spark.log", "log\n");

        // An added plugin: one config, its translations, its database and its licence.
        write("CoreProtect/config.yml", "use-mysql: false\n");
        write("CoreProtect/language.yml", "x: y\n");
        write("CoreProtect/database.db", "SQLite format 3\n");
        write("CoreProtect/.license", "key\n");

        // Nested folders, every listed extension, and every excluded kind beside them.
        write("FastAsyncWorldEdit/worldedit-config.yml", "a: 1\n");
        write("FastAsyncWorldEdit/config.yaml", "a: 1\n");
        write("FastAsyncWorldEdit/limits/default.properties", "a=1\n");
        write("FastAsyncWorldEdit/limits/deep/rules.toml", "a = 1\n");
        write("FastAsyncWorldEdit/limits/deep/server.conf", "a 1\n");
        write("FastAsyncWorldEdit/readme.TXT", "hi\n");
        write("FastAsyncWorldEdit/config.schema.json", "{}\n");
        write("FastAsyncWorldEdit/config.yml.bak", "a: 0\n");
        write("FastAsyncWorldEdit/messages/en.yml", "x: y\n");
        write("FastAsyncWorldEdit/colors.yml", "red: '#f00'\n");
        write("FastAsyncWorldEdit/nametags.yml", "a: 1\n");
        write("FastAsyncWorldEdit/translations.properties", "a=b\n");
        write("FastAsyncWorldEdit/schematics/house.schem", "x\n");
        write("FastAsyncWorldEdit/worldedit.jar", "PK\n");
        Files.write(plugins.resolve("FastAsyncWorldEdit/binary.yml"), new byte[] {'a', ':', 0, '1'});
        Files.write(plugins.resolve("FastAsyncWorldEdit/huge.yml"), new byte[(int) PluginFiles.MAX_SIZE + 1]);
        Files.writeString(plugins.resolve("FastAsyncWorldEdit/large.yml"), "a".repeat((int) PluginFiles.MAX_SIZE));
        Files.createSymbolicLink(plugins.resolve("FastAsyncWorldEdit/link.yml"), Path.of("config.yaml"));
        Files.createSymbolicLink(plugins.resolve("FastAsyncWorldEdit/linked"), plugins.resolve("spark"));

        // A folder no jar claims, and a link to a folder outside the volume.
        write("Orphan/settings.yml", "a: 1\n");
        final Path outside = Files.createDirectories(scratch.resolve("outside"));
        Files.writeString(outside.resolve("secret.yml"), "token: x\n");
        Files.createSymbolicLink(plugins.resolve("Outside"), outside);

        // A loose file and a jar beside the folders.
        write("loose.yml", "a: 1\n");
        write("spark-1.10.0.jar", "PK\n");
    }

    private List<String> listed() {
        return client.pluginFiles("smp").files().stream()
                .map(file -> file.plugin() + "/" + file.path())
                .toList();
    }

    @Test
    void exactlyTheForeignTextConfigsAreListedByPluginThenPath() throws IOException {
        fixture();

        assertEquals(
                List.of(
                        "CoreProtect/config.yml",
                        "FastAsyncWorldEdit/config.yaml",
                        "FastAsyncWorldEdit/large.yml",
                        "FastAsyncWorldEdit/limits/deep/rules.toml",
                        "FastAsyncWorldEdit/limits/deep/server.conf",
                        "FastAsyncWorldEdit/limits/default.properties",
                        "FastAsyncWorldEdit/readme.TXT",
                        "FastAsyncWorldEdit/worldedit-config.yml",
                        "Orphan/settings.yml",
                        "spark/config.json"),
                listed());
    }

    @Test
    void aFileCarriesItsPluginsNameAndIconAndAnUnclaimedFolderNeither() throws IOException {
        fixture();
        final Map<String, AgentWire.PluginFile> files = client.pluginFiles("smp").files().stream()
                .collect(Collectors.toMap(file -> file.plugin() + "/" + file.path(), file -> file));

        final AgentWire.PluginFile spark = files.get("spark/config.json");
        assertEquals("spark", spark.name());
        assertEquals(ICON, spark.iconUrl());
        assertEquals(3, spark.size());
        assertTrue(spark.readable() && spark.writable());

        final AgentWire.PluginFile orphan = files.get("Orphan/settings.yml");
        assertNull(orphan.name());
        assertNull(orphan.iconUrl());
    }

    @Test
    void aPathIsFoundInTheListingAndNeverJoined() throws IOException {
        fixture();

        for (final String[] asked : List.of(
                new String[] {"CoreProtect", "../smp/config.yml"},
                new String[] {"smp", "config.yml"},
                new String[] {"Outside", "secret.yml"},
                new String[] {"FastAsyncWorldEdit", "link.yml"},
                new String[] {"FastAsyncWorldEdit", "linked/config.json"},
                new String[] {"FastAsyncWorldEdit", "./config.yaml"},
                new String[] {"..", "outside/secret.yml"})) {
            final InternalClient.Failure failure =
                    assertThrows(InternalClient.Failure.class, () -> client.pluginFile("smp", asked[0], asked[1]));
            assertEquals(404, failure.status(), String.join("/", asked));
        }
        assertEquals(
                404,
                assertThrows(InternalClient.Failure.class, () -> client.pluginFile("limbo", "spark", "config.json"))
                        .status());
    }

    @Test
    void aReadAnswersTheTextAndItsRevision() throws IOException {
        write("CoreProtect/config.yml", "use-mysql: false # ä\n");

        final AgentWire.PluginFileDocument document = client.pluginFile("smp", "CoreProtect", "config.yml");

        assertEquals("use-mysql: false # ä\n", document.text());
        assertEquals(Sha256.hex(document.text()), document.revision());
    }

    @Test
    void aSaveReplacesTheFileKeepsItsModeAndAsksForARestart() throws IOException {
        final Path file = write("CoreProtect/config.yml", "use-mysql: false\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        final String revision =
                client.pluginFile("smp", "CoreProtect", "config.yml").revision();

        final AgentWire.PluginFileSaved saved = client.savePluginFile(
                "smp",
                "CoreProtect",
                "config.yml",
                new AgentWire.PluginFileSave("use-mysql: true\n", revision, Actor.HOST));

        assertEquals("use-mysql: true\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals(Sha256.hex("use-mysql: true\n"), saved.revision());
        assertEquals(16, saved.file().size());
        assertTrue(saved.restartRequired());
        // No temporary file is left beside it.
        try (var left = Files.list(file.getParent())) {
            assertEquals(List.of(file), left.toList());
        }
    }

    @Test
    void aSaveFromAnOlderRevisionIsRefusedAndWritesNothing() throws IOException {
        final Path file = write("CoreProtect/config.yml", "use-mysql: false\n");
        final String revision =
                client.pluginFile("smp", "CoreProtect", "config.yml").revision();
        Files.writeString(file, "use-mysql: false\nchanged: on disk\n");

        final InternalClient.Failure failure = assertThrows(
                InternalClient.Failure.class,
                () -> client.savePluginFile(
                        "smp",
                        "CoreProtect",
                        "config.yml",
                        new AgentWire.PluginFileSave("use-mysql: true\n", revision, Actor.HOST)));

        assertEquals(409, failure.status());
        assertEquals("use-mysql: false\nchanged: on disk\n", Files.readString(file));
    }

    @Test
    void aSaveWithoutWhoSavedItIsRefused() throws IOException {
        write("CoreProtect/config.yml", "a: 1\n");

        final InternalClient.Failure failure = assertThrows(
                InternalClient.Failure.class,
                () -> agent.client()
                        .post(
                                AgentWire.of(AgentWire.PLUGIN_FILE, "smp") + "?plugin=CoreProtect&path=config.yml",
                                "{\"text\":\"a: 2\\n\",\"revision\":\"" + Sha256.hex("a: 1\n") + "\"}"));

        assertEquals(400, failure.status());
    }
}
