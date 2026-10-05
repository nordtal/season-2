package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** Holds the tests' PostgreSQL against the one the stack runs, so a test never proves a different version. */
class TestDatabaseImageTest {

    @Test
    void theTestImageIsComposesDefault() throws IOException {
        final String compose = Files.readString(RepositoryRoot.resolve("compose.yml"));
        final String line = "image: ${POSTGRES_IMAGE:-" + TestDatabase.IMAGE + "}";
        assertTrue(compose.contains(line), "compose.yml's postgres service does not read `" + line + "`");
    }
}
