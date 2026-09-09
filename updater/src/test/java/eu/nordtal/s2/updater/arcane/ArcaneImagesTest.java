package eu.nordtal.s2.updater.arcane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arcane's project-updates payload, read the way the updater reads it.
 *
 * <p>The bodies below are the shape of Arcane v2.10.2's own response, written from its source on
 * 2026-09-09 - {@code types/project/project.go} and
 * {@code backend/internal/project/project_details.go}. <b>They are not a recording of a live
 * Arcane</b>, which nobody here has yet made: the field names are read out of the type definitions
 * and this file is what pins them, so an Arcane that renames one fails here rather than by silently
 * never renewing an image again. Putting a real capture in
 * {@code src/test/resources/fixtures/} is an item in nordtal/todo.md (A19).</p>
 */
class ArcaneImagesTest {

    /** The four Minecraft services share one image, which is the whole reason for the join. */
    private static final String BODY = """
            {
              "id": "51b523fe",
              "name": "nordtal-s2",
              "services": [
                {"name": "smp",             "image": "ghcr.io/nordtal/minecraft:latest"},
                {"name": "hunger-games",    "image": "ghcr.io/nordtal/minecraft:latest"},
                {"name": "limbo",           "image": "ghcr.io/nordtal/minecraft:latest"},
                {"name": "network-control", "image": "ghcr.io/nordtal/minecraft:latest"},
                {"name": "discord-bot",     "image": "ghcr.io/nordtal/discord-bot:latest"},
                {"name": "postgres",        "image": "postgres:17-alpine"}
              ],
              "updateInfo": {
                "status": "has_update",
                "hasUpdate": true,
                "imageCount": 3,
                "updatedImageRefs": ["ghcr.io/nordtal/minecraft:latest"],
                "updateInfoByRef": {
                  "ghcr.io/nordtal/minecraft:latest":  {"hasUpdate": true,  "currentVersion": "0.8.0", "latestVersion": "0.8.1"},
                  "ghcr.io/nordtal/discord-bot:latest": {"hasUpdate": false, "currentVersion": "0.8.1", "latestVersion": "0.8.1"}
                }
              }
            }
            """;

    @Test
    @DisplayName("one stale image reference is every service that runs it")
    void oneReferenceIsFourServices() {
        final Map<String, ImageResult.State> states = ArcaneImages.parse(BODY);

        // The join is the reason this class exists. Arcane answers keyed by image and the run acts
        // per service, and nothing upstream connects the two.
        assertEquals(ImageResult.State.OUTDATED, states.get("smp"));
        assertEquals(ImageResult.State.OUTDATED, states.get("hunger-games"));
        assertEquals(ImageResult.State.OUTDATED, states.get("limbo"));
        assertEquals(ImageResult.State.OUTDATED, states.get("network-control"));
    }

    @Test
    @DisplayName("a checked and current image is up to date, and is not work")
    void aCurrentImageIsUpToDate() {
        assertEquals(ImageResult.State.UP_TO_DATE, ArcaneImages.parse(BODY).get("discord-bot"));
    }

    @Test
    @DisplayName("an image nobody has checked is UNKNOWN, never up to date")
    void anUncheckedImageIsUnknown() {
        // postgres is in services[] and in neither half of updateInfo. Calling that "up to date"
        // is the failure this whole three-state answer exists to prevent: it reads as a network
        // that has been checked and is current, for ever, without anybody having looked once.
        assertEquals(ImageResult.State.UNKNOWN, ArcaneImages.parse(BODY).get("postgres"));
    }

    @Test
    @DisplayName("a project Arcane has never checked says so instead of saying nothing is stale")
    void anUncheckedProjectSaysSo() {
        final ImageResult result = ImageResult.of(ArcaneImages.parse("""
                {"services": [{"name": "smp", "image": "ghcr.io/nordtal/minecraft:latest"}]}
                """));

        assertEquals(ImageResult.State.UNKNOWN, result.state("smp"));
        assertFalse(result.isOutdated("smp"));
        assertTrue(result.nothingChecked().isPresent(),
                "a run where nothing could be checked looks exactly like one where every image is"
                        + " current, and this sentence is the only thing that tells them apart");
    }

    @Test
    @DisplayName("the per-reference map alone is enough - the summary list is not required")
    void thePerReferenceMapAlone() {
        // Both halves are read and the union taken, so a summary list that stops being filled in a
        // later Arcane cannot turn "outdated" into "up to date" in silence.
        final Map<String, ImageResult.State> states = ArcaneImages.parse("""
                {
                  "services": [{"name": "smp", "image": "img:1"}],
                  "updateInfo": {"updateInfoByRef": {"img:1": {"hasUpdate": true}}}
                }
                """);
        assertEquals(ImageResult.State.OUTDATED, states.get("smp"));
    }

    @Test
    @DisplayName("a service with no image of its own is never recreated on a guess")
    void aServiceWithoutAnImage() {
        // A service Arcane could not resolve a config for. Recreating a container because a field
        // was missing is the one outcome this path must never produce.
        final Map<String, ImageResult.State> states = ArcaneImages.parse("""
                {
                  "services": [{"name": "smp"}],
                  "updateInfo": {"updatedImageRefs": ["img:1"]}
                }
                """);
        assertEquals(ImageResult.State.UNKNOWN, states.get("smp"));
    }

    @Test
    @DisplayName("a body wrapped in Arcane's envelope is read the same way")
    void aWrappedBody() {
        final Map<String, ImageResult.State> states = ArcaneImages.parse("""
                {"data": {
                  "services": [{"name": "smp", "image": "img:1"}],
                  "updateInfo": {"updatedImageRefs": ["img:1"]}
                }}
                """);
        assertEquals(ImageResult.State.OUTDATED, states.get("smp"));
    }

    @Test
    @DisplayName("nothing parseable is no services, which is never work")
    void rubbishIsNotWork() {
        // Being generous about the shape costs an image that is never renewed; being strict costs
        // an outage. Both directions end in "no services", and no service is ever recreated on one.
        assertEquals(Map.of(), ArcaneImages.parse(""));
        assertEquals(Map.of(), ArcaneImages.parse("[]"));
        assertEquals(Map.of(), ArcaneImages.parse("{\"nothing\": 1}"));
    }
}
