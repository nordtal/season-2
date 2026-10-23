package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;
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

    @Test
    void aRouteThatGetsItsHandlerAsAValueFailsWithItsPath() {
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> TextPages.Server.answers(TextPages.Server.classesOf(TextPagesTest.class)
                        .resolve(Routes.class.getName().replace('.', '/') + ".class")));

        assertTrue(failure.getMessage().contains("/api/handed-in"), failure.getMessage());
        assertFalse(failure.getMessage().contains("/api/seen"), failure.getMessage());
    }

    /** One route with a lambda, which the map follows, and one with a handler handed in, which it cannot. */
    static final class Routes {

        static void register(final JavalinConfig cfg, final Handler handedIn) {
            cfg.routes.get("/api/seen", ctx -> ctx.result("seen"));
            cfg.routes.get("/api/handed-in", handedIn);
        }
    }
}
