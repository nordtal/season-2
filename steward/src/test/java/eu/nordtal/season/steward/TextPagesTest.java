package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** Checks that the committed page map is the one the sources give, which also holds every text to a page. */
class TextPagesTest {

    @Test
    void theCommittedPageMapIsTheOneTheSourcesGive() throws IOException {
        assertEquals(
                TextPages.render(),
                Files.readString(TextPages.TARGET, StandardCharsets.UTF_8),
                "frontend/src/lib/text-pages.gen.json is stale: run ./gradlew :steward:generateApiTypes");
    }
}
