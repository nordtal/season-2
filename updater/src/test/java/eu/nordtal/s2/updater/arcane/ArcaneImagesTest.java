package eu.nordtal.s2.updater.arcane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arcane's project-updates payload, read the way the updater reads it.
 *
 * <h2>These bodies are cut down from a real response now, not from type definitions</h2>
 * The previous version of this file was written from Arcane's source - and it invented a
 * {@code name} field on the entries of {@code services[]} that the real endpoint does not send.
 * Every test passed, and the feature had never once worked: {@code parse} skipped every nameless
 * entry, returned an empty map, and every update report carried a note blaming a setting in Arcane
 * that was in fact switched on. A fixture that is written rather than recorded tests the writer's
 * belief, which is exactly the belief that was wrong.
 *
 * <p>What is below is trimmed from the response of the live Arcane v2.10.2 on the dev host,
 * 2026-09-11. <b>It is trimmed and not pasted:</b> the real {@code services[]} entries carry a full
 * {@code environment} block, which on that host holds the Discord token, the bunq key and the
 * database password - and this repository is public. The shape that matters here is kept and the
 * secrets are not in it.</p>
 */
class ArcaneImagesTest {

    /**
     * The updates payload, as it really arrives: {@code services[]} entries are compose service
     * <em>configs</em> and carry no name of any kind.
     */
    private static final String UPDATES = """
            {
              "id": "51b523fe",
              "name": "nordtal-s2",
              "hasBuildDirective": true,
              "services": [
                {"image": "ghcr.io/nordtal/minecraft:latest",    "build": {"context": "/x"}, "restart": "unless-stopped"},
                {"image": "ghcr.io/nordtal/minecraft:latest",    "build": {"context": "/x"}, "restart": "unless-stopped"},
                {"image": "ghcr.io/nordtal/minecraft:latest",    "build": {"context": "/x"}, "restart": "unless-stopped"},
                {"image": "ghcr.io/nordtal/minecraft:latest",    "build": {"context": "/x"}, "restart": "unless-stopped"},
                {"image": "ghcr.io/nordtal/discord-bot:latest",  "build": {"context": "/y"}, "restart": "unless-stopped"},
                {"image": "postgres:17-alpine",                  "restart": "unless-stopped"}
              ],
              "updateInfo": {
                "status": "has_update",
                "hasUpdate": true,
                "imageCount": 3,
                "updatedImageRefs": ["ghcr.io/nordtal/minecraft:latest"],
                "updateInfoByRef": {
                  "ghcr.io/nordtal/minecraft:latest":   {"updateType": "digest", "hasUpdate": true,  "currentVersion": "0.8.0", "latestVersion": "0.8.1", "latestDigest": "sha256:bbb"},
                  "ghcr.io/nordtal/discord-bot:latest": {"updateType": "digest", "hasUpdate": false, "currentVersion": "0.8.1", "latestVersion": "0.8.1", "latestDigest": "sha256:aaa"}
                }
              }
            }
            """;

    /** The runtime payload, which is the one that carries a name next to the image. */
    private static final String RUNTIME = """
            {"data": {
              "name": "nordtal-s2",
              "runtimeServices": [
                {"name": "smp",             "image": "ghcr.io/nordtal/minecraft:latest",   "status": "running", "health": "healthy"},
                {"name": "hunger-games",    "image": "ghcr.io/nordtal/minecraft:latest",   "status": "running", "health": "healthy"},
                {"name": "limbo",           "image": "ghcr.io/nordtal/minecraft:latest",   "status": "running", "health": "healthy"},
                {"name": "network-control", "image": "ghcr.io/nordtal/minecraft:latest",   "status": "running", "health": "healthy"},
                {"name": "discord-bot",     "image": "ghcr.io/nordtal/discord-bot:latest", "status": "running", "health": "healthy"},
                {"name": "postgres",        "image": "postgres:17-alpine",                 "status": "running", "health": "healthy"}
              ]
            }}
            """;

    @Test
    @DisplayName("the updates payload names no service, so the names come from the runtime one")
    void theNamesComeFromTheRuntimePayload() {
        // THE REGRESSION. Parsed without the runtime body, the real updates payload yields nothing
        // at all - which is what shipped, and what made every report blame Arcane's configuration.
        assertTrue(ArcaneImages.parse(UPDATES, null).services().isEmpty(),
                "the updates payload alone still names nothing - if this ever stops being true,"
                        + " Arcane has started sending names and the fallback in named() takes over");

        assertFalse(ArcaneImages.parse(UPDATES, RUNTIME).services().isEmpty(),
                "with the runtime body there are services again, and the image-drift feature is"
                        + " only alive when there are");
    }

    @Test
    @DisplayName("one stale image reference is every service that runs it")
    void oneReferenceIsFourServices() {
        final ImageResult result = ArcaneImages.parse(UPDATES, RUNTIME);

        // The join is the reason this class exists. Arcane answers keyed by image and the run acts
        // per service, and nothing upstream connects the two.
        assertEquals(ImageResult.State.OUTDATED, result.state("smp"));
        assertEquals(ImageResult.State.OUTDATED, result.state("hunger-games"));
        assertEquals(ImageResult.State.OUTDATED, result.state("limbo"));
        assertEquals(ImageResult.State.OUTDATED, result.state("network-control"));
    }

    @Test
    @DisplayName("a checked and current image is up to date, and is not work")
    void aCurrentImageIsUpToDate() {
        assertEquals(ImageResult.State.UP_TO_DATE,
                ArcaneImages.parse(UPDATES, RUNTIME).state("discord-bot"));
    }

    @Test
    @DisplayName("an image nobody has checked is UNKNOWN, never up to date")
    void anUncheckedImageIsUnknown() {
        // postgres is in the runtime list and in neither half of updateInfo. Calling that "up to
        // date" is the failure this three-state answer exists to prevent: it reads as a network
        // that has been checked and is current, for ever, without anybody having looked once.
        assertEquals(ImageResult.State.UNKNOWN,
                ArcaneImages.parse(UPDATES, RUNTIME).state("postgres"));
    }

    @Test
    @DisplayName("`updateType: local` is not a check, and is never reported as up to date")
    void aLocalImageIsNotAnAnswer() {
        // Arcane classifies the image of any service with a `build:` directive as local: it reports
        // the digest it already has and never asks a registry. hasUpdate is false there because
        // nothing was compared, not because nothing has changed - and every image this project
        // publishes is in exactly that position.
        final ImageResult result = ArcaneImages.parse("""
                {
                  "updateInfo": {
                    "status": "up_to_date",
                    "hasUpdate": false,
                    "updateInfoByRef": {
                      "ghcr.io/nordtal/updater:latest": {"updateType": "local", "hasUpdate": false,
                        "currentVersion": "latest", "latestVersion": "", "latestDigest": ""}
                    }
                  }
                }
                """, """
                {"runtimeServices": [{"name": "updater", "image": "ghcr.io/nordtal/updater:latest"}]}
                """);

        assertEquals(ImageResult.State.UNKNOWN, result.state("updater"));
        assertFalse(result.isOutdated("updater"));
        assertEquals(java.util.Set.of("updater"), result.notCheckable());

        final String note = result.nothingChecked().orElseThrow(
                () -> new AssertionError("a run that compared nothing has to say so"));
        assertTrue(note.contains("build:"), "the note must name the real cause rather than send"
                + " somebody to a setting that is already on: " + note);
        assertFalse(note.contains("turn the image update check on"),
                "that sentence is for a project with no results at all, not for one whose results"
                        + " all say 'local': " + note);
    }

    @Test
    @DisplayName("a project Arcane has never checked says so instead of saying nothing is stale")
    void anUncheckedProjectSaysSo() {
        final ImageResult result = ArcaneImages.parse(
                "{}", """
                {"runtimeServices": [{"name": "smp", "image": "ghcr.io/nordtal/minecraft:latest"}]}
                """);

        assertEquals(ImageResult.State.UNKNOWN, result.state("smp"));
        assertFalse(result.isOutdated("smp"));
        assertTrue(result.nothingChecked().isPresent(),
                "a run where nothing could be checked looks exactly like one where every image is"
                        + " current, and this sentence is the only thing that tells them apart");
        assertTrue(result.nothingChecked().orElseThrow().contains("turn the image update check on"),
                "with no results at all the setting really is the thing to check");
    }

    @Test
    @DisplayName("the per-reference map alone is enough - the summary list is not required")
    void thePerReferenceMapAlone() {
        // Both halves are read and the union taken, so a summary list that stops being filled in a
        // later Arcane cannot turn "outdated" into "up to date" in silence.
        final ImageResult result = ArcaneImages.parse("""
                {"updateInfo": {"updateInfoByRef": {"img:1": {"updateType": "digest", "hasUpdate": true}}}}
                """, """
                {"runtimeServices": [{"name": "smp", "image": "img:1"}]}
                """);
        assertEquals(ImageResult.State.OUTDATED, result.state("smp"));
    }

    @Test
    @DisplayName("a service with no image of its own is never recreated on a guess")
    void aServiceWithoutAnImage() {
        // A service Arcane could not resolve a config for. Recreating a container because a field
        // was missing is the one outcome this path must never produce.
        final ImageResult result = ArcaneImages.parse("""
                {"updateInfo": {"updatedImageRefs": ["img:1"]}}
                """, """
                {"runtimeServices": [{"name": "smp"}]}
                """);
        assertEquals(ImageResult.State.UNKNOWN, result.state("smp"));
        assertFalse(result.isOutdated("smp"));
    }

    @Test
    @DisplayName("a body wrapped in Arcane's envelope is read the same way, on both halves")
    void aWrappedBody() {
        final ImageResult result = ArcaneImages.parse("""
                {"data": {"updateInfo": {"updatedImageRefs": ["img:1"]}}}
                """, """
                {"data": {"runtimeServices": [{"name": "smp", "image": "img:1"}]}}
                """);
        assertEquals(ImageResult.State.OUTDATED, result.state("smp"));
    }

    @Test
    @DisplayName("a services[] that does carry names still works, so a later Arcane needs no change")
    void namesInTheUpdatesPayloadStillWork() {
        final ImageResult result = ArcaneImages.parse("""
                {
                  "services": [{"name": "smp", "image": "img:1"}],
                  "updateInfo": {"updatedImageRefs": ["img:1"]}
                }
                """, null);
        assertEquals(ImageResult.State.OUTDATED, result.state("smp"));
    }

    @Test
    @DisplayName("nothing parseable is no services, which is never work")
    void rubbishIsNotWork() {
        // Being generous about the shape costs an image that is never renewed; being strict costs
        // an outage. Both directions end in "no services", and no service is ever recreated on one.
        assertTrue(ArcaneImages.parse("", null).services().isEmpty());
        assertTrue(ArcaneImages.parse("[]", null).services().isEmpty());
        assertTrue(ArcaneImages.parse("{\"nothing\": 1}", null).services().isEmpty());
        // An unparseable runtime body must not throw - it is read in a catch-nothing helper.
        assertTrue(ArcaneImages.parse(UPDATES, "not json at all").services().isEmpty());
    }
}
