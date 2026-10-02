package eu.nordtal.s2.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** The committed TypeScript types are the ones the Java records give, so the browser and steward agree. */
class ApiTypesTest {

    @Test
    void theCommittedTypesAreTheOnesTheRecordsGive() throws IOException {
        assertEquals(
                ApiTypes.render(),
                Files.readString(ApiTypes.TARGET, StandardCharsets.UTF_8),
                "frontend/src/lib/api.gen.ts is stale: run ./gradlew :steward:generateApiTypes");
    }
}
