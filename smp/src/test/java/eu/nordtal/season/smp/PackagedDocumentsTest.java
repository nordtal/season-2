package eu.nordtal.season.smp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.messages.document.Documents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every document the module words is packaged, so none throws when the server first sends it. */
class PackagedDocumentsTest {

    @Test
    void everyDocumentOfTheModuleIsDeclared() throws IOException {
        final List<String> unpackaged;
        try (Stream<Path> files = Files.list(RepositoryRoot.resolve("smp/src/main/resources/documents/smp"))) {
            final var packaged = Documents.packaged(PackagedDocumentsTest.class.getClassLoader(), "smp");
            unpackaged = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json") && !name.equals("declarations.json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .filter(name -> !packaged.containsKey(name))
                    .sorted()
                    .toList();
        }
        assertEquals(List.of(), unpackaged, "no class in messageSpec.documentClasses declares these documents");
    }
}
