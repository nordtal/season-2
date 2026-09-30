package eu.nordtal.s2.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class FileSettingsTest {

    @TempDir
    Path folder;

    private FileSettings settings() {
        return FileSettings.in(folder, "NORDTAL_TEST", LoggerFactory.getLogger(FileSettingsTest.class));
    }

    @Test
    void aMissingFileIsWrittenWithTheDefaultsAndItsOverrideMarker() throws SettingsException {
        final Setting<DatabaseSpec> database = settings().load("database", DatabaseSpec.class, DatabasePool::check);

        assertEquals("jdbc:postgresql://localhost:5432/nordtal", database.get().jdbcUrl());
        assertTrue(Files.isRegularFile(folder.resolve("database.yml")));
        assertTrue(Files.isRegularFile(folder.resolve("database" + EnvOverrideFile.SUFFIX)));
    }

    @Test
    void theMainGroupTakesTheBarePrefixAndEveryOtherOneItsName() {
        assertEquals("NORDTAL_TEST", settings().prefixOf("config"));
        assertEquals("NORDTAL_TEST_DATABASE", settings().prefixOf("database"));
        assertEquals("NORDTAL_TEST_SOUND_EFFECTS", settings().prefixOf("sound-effects"));
    }

    @Test
    void aValueTheCheckRefusesIsASettingsExceptionNamingTheFile() throws IOException {
        Files.writeString(folder.resolve("database.yml"), "maximum-pool-size: 0\n");

        final SettingsException refused = assertThrows(
                SettingsException.class, () -> settings().load("database", DatabaseSpec.class, DatabasePool::check));
        assertTrue(refused.getMessage().startsWith("database.yml: "), refused.getMessage());
        assertTrue(refused.getMessage().contains("maximum-pool-size"), refused.getMessage());
    }

    @Test
    void aReloadTheCheckRefusesKeepsTheValuesInUse() throws SettingsException, IOException {
        final Setting<DatabaseSpec> database = settings().load("database", DatabaseSpec.class, DatabasePool::check);
        Files.writeString(folder.resolve("database.yml"), "jdbc-url: 'mysql://elsewhere'\n");

        assertThrows(SettingsException.class, database::reload);
        assertEquals("jdbc:postgresql://localhost:5432/nordtal", database.get().jdbcUrl());
    }
}
