package eu.nordtal.season.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvFileTest {

    @TempDir
    Path directory;

    private EnvFile file(final String... lines) throws IOException {
        final Path path = directory.resolve("dev.env");
        Files.write(path, List.of(lines), StandardCharsets.UTF_8);
        return new EnvFile(path);
    }

    @Test
    void aValueIsReadWithOneLayerOfQuotesTakenOff() throws IOException {
        final EnvFile env = file("# A=commented", "A=\"quoted\"", "export B='single'", "C=a(b", "D=");
        assertEquals(Optional.of("quoted"), env.value("A"));
        assertEquals(Optional.of("single"), env.value("B"));
        assertEquals(Optional.of("a(b"), env.value("C"), "a password is not a shell expression");
        assertEquals(Optional.of(""), env.value("D"));
        assertEquals(Optional.empty(), env.value("E"));
    }

    @Test
    void settingReplacesEveryAssignmentWhateverItsSpellingAndAppendsWhenThereIsNone() throws IOException {
        final EnvFile env = file("# keep me", "  A=old", "export A=older", "B=b");
        env.set("A", "new");
        env.set("C", "c");
        assertEquals(
                List.of("# keep me", "A=new", "A=new", "B=b", "C=c"),
                Files.readAllLines(env.path(), StandardCharsets.UTF_8));
    }

    @Test
    void replaceMeIsNotAValueAndIsReportedByLineNumberOnly() throws IOException {
        final EnvFile env = file("# REPLACE_ME is explained here", "A=REPLACE_ME", "B=set", "C=  ");
        assertFalse(env.isSet("A"));
        assertTrue(env.isSet("B"));
        assertFalse(env.isSet("C"));
        assertEquals(List.of(2), env.replaceMeLines());
    }
}
