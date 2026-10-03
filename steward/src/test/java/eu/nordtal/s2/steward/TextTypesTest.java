package eu.nordtal.s2.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** The frontend's packaged texts and their types are the ones the bundles give, so a test renders what ships. */
class TextTypesTest {

    @Test
    void theCommittedTypesAreTheOnesTheSpecsGive() throws IOException {
        assertEquals(
                TextTypes.types(),
                Files.readString(TextTypes.TYPES, StandardCharsets.UTF_8),
                "frontend/src/lib/texts.gen.ts is stale: run ./gradlew :steward:generateApiTypes");
    }

    @Test
    void theCommittedTextsAreTheOnesTheBundlesGive() throws IOException {
        assertEquals(
                TextTypes.texts(),
                Files.readString(TextTypes.TEXTS, StandardCharsets.UTF_8),
                "frontend/src/lib/texts.gen.json is stale: run ./gradlew :steward:generateApiTypes");
    }
}
