package eu.nordtal.s2.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.database.Actor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** That a settings file left from before the database is imported once, as what differs from the spec's default. */
class LegacyImportTest {

    private static final Group<ExampleSpec> EXAMPLE =
            Group.of("example", ExampleSpec.class).defaulting("view-distance", 32);

    private final MemorySettingStore store = new MemorySettingStore();

    @TempDir
    Path folder;

    @Test
    void whatDiffersFromTheSpecsDefaultIsImportedAndNothingElse() throws Exception {
        write("example.yml", """
                view-distance: 0
                name: custom
                token: never-stored
                retired: true
                nested:
                  words: [a, b]
                """);

        final ExampleSpec values = settings(Set.of()).load(EXAMPLE).get();

        assertEquals("custom", values.name());
        assertEquals(List.of("a", "b"), values.nested().words());
        assertEquals(32, values.viewDistance(), "a 0 the file was given as its default stays the process's default");
        assertEquals(
                List.of("name", "nested.words"),
                store.overrides(List.of("smp")).stream()
                        .map(value -> value.path())
                        .toList());
        assertEquals(Actor.HOST, store.actorAt("smp", "example", "name"));
    }

    @Test
    void anAdminsValueIsNeverReplacedByAFile() throws Exception {
        store.set("smp", "example", "name", "from-steward");
        write("example.yml", "name: from-the-file\n");

        assertEquals("from-steward", settings(Set.of()).load(EXAMPLE).get().name());
    }

    @Test
    void aSkippedPathAndWhatTheEnvironmentHoldsAreNotImported() throws Exception {
        write("example.yml", """
                name: custom
                nested:
                  words: [b]
                """);
        final Map<String, String> variables = Map.of("NORDTAL_TEST_EXAMPLE_NAME", "from-the-host");

        DatabaseSettings.over(store, "smp", Environment.of("NORDTAL_TEST").reading(variables::get), logger())
                .importingFrom(folder, Set.of("example/nested.words"))
                .load(EXAMPLE);

        assertEquals(List.of(), store.overrides(List.of("smp")));
    }

    @Test
    void retiringDeletesTheFilesOfEveryLoadedGroupAndOfTheBootstrapOnly() throws Exception {
        for (final String name : List.of(
                "example.yml",
                "example.yml.bak",
                "example.yml.bak-20260929T204717Z",
                "example.schema.json",
                "example.env-overrides.txt",
                "database.yml",
                "database.schema.json",
                "steward-ui.yml",
                "icon.png")) {
            write(name, "name: x\n");
        }
        Files.createDirectories(folder.resolve("messages"));
        write("messages/en.properties", "a=b\n");
        final DatabaseSettings settings = settings(Set.of());
        settings.load(EXAMPLE);

        settings.retireFiles();

        assertEquals(List.of("icon.png", "messages", "steward-ui.yml"), remaining());
    }

    @Test
    void aFileThatCannotBeReadStaysWhereItIs() throws Exception {
        write("example.yml", "- a list\n- not a mapping\n");
        final DatabaseSettings settings = settings(Set.of());

        assertEquals("nordtal", settings.load(EXAMPLE).get().name());
        settings.retireFiles();

        assertEquals(List.of("example.yml"), remaining());
    }

    private DatabaseSettings settings(final Set<String> skipped) {
        return store.settings("smp").importingFrom(folder, skipped);
    }

    private void write(final String name, final String content) throws Exception {
        Files.writeString(folder.resolve(name), content);
    }

    private List<String> remaining() throws Exception {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static org.slf4j.Logger logger() {
        return org.slf4j.LoggerFactory.getLogger(LegacyImportTest.class);
    }
}
