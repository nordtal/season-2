package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** Steward's blue is the brand tone the game and the bot paint, written from it. */
class BrandCssTest {

    @Test
    void theCommittedBlueIsTheBrandTone() throws IOException {
        assertEquals(
                BrandCss.render(),
                Files.readString(BrandCss.TARGET, StandardCharsets.UTF_8),
                "frontend/src/brand.gen.css is stale: run ./gradlew :steward:generateApiTypes");
    }
}
