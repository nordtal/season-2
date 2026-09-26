package eu.nordtal.s2.steward.deployer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A rotated env file behind a stale FILE bind mount, and the one signal that catches it.
 *
 * A host rotation after the container started leaves this process reading a deleted file forever,
 * silently. The one signal that survives the mount is the orphaned inode's own link count, which
 * drops to zero - this class is what makes {@link Compose#assertEnvFileFresh()} refuse on that
 * signal, and never anything else.
 *
 * These tests need no Docker daemon and no real bind mount, on purpose, the same way
 * {@link ComposeRefusesItselfTest} needs none: a real orphaned inode only exists behind an actual
 * mount, and faking the link count is what lets the refusal itself be exercised in a plain JVM. A
 * real file on disk, with its real (healthy) link count, is used for the "nothing wrong" case so the
 * fake is only ever standing in for the one number this class cannot otherwise get to zero.
 */
class EnvFileFreshnessTest {

    private Path tempEnvFile;

    @AfterEach
    void cleanUp() throws IOException {
        if (tempEnvFile != null) {
            Files.deleteIfExists(tempEnvFile);
        }
    }

    @Test
    void aLinkCountOfZeroIsRefusedAndTheMessageNamesTheDeletedInode() throws IOException {
        tempEnvFile = Files.createTempFile("env-file-", ".env");
        final Compose compose = new Compose(
                Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"), "nordtal-s2", path -> OptionalLong.of(0L));

        final IOException refused = assertThrows(Compose.StaleEnvFileException.class, compose::assertEnvFileFresh);

        assertTrue(refused.getMessage().contains("deleted inode"), refused.getMessage());
    }

    @Test
    void aHealthyLinkCountIsNotRefused() throws IOException {
        tempEnvFile = Files.createTempFile("env-file-", ".env");
        final Compose compose = new Compose(
                Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"), "nordtal-s2", path -> OptionalLong.of(1L));

        assertDoesNotThrow(compose::assertEnvFileFresh);
    }

    @Test
    void theRealPosixBackedLinkCountOfAnOrdinaryFileIsNotZero() throws IOException {
        // No fake here: the default LinkCounter this class wires in must read an ordinary file as healthy too.
        tempEnvFile = Files.createTempFile("env-file-", ".env");
        Files.writeString(tempEnvFile, "DEMO_VALUE=before\n");
        final Compose compose = new Compose(Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"), "nordtal-s2");

        assertDoesNotThrow(compose::assertEnvFileFresh);
    }

    @Test
    void aMissingFileIsNotThisChecksProblemBaseAlreadyLeavesItOut() {
        final Compose compose = new Compose(
                Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2", path -> {
                    throw new AssertionError("the link counter must not even be asked about a path"
                            + " that does not exist - there is nothing orphaned to detect");
                });

        assertDoesNotThrow(compose::assertEnvFileFresh);
    }

    @Test
    void upRefusesBeforeItEverBuildsACommandLineLetAloneRunsOne() throws IOException {
        tempEnvFile = Files.createTempFile("env-file-", ".env");
        final Compose compose = new Compose(
                Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"), "nordtal-s2", path -> OptionalLong.of(0L));

        assertThrows(Compose.StaleEnvFileException.class, () -> compose.up(List.of("smp"), line -> {}));
    }

    @Test
    void bootstrapRefusesTooItIsThePathDeployerUpTakes() throws IOException {
        tempEnvFile = Files.createTempFile("env-file-", ".env");
        final Compose compose = new Compose(
                Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"), "nordtal-s2", path -> OptionalLong.of(0L));

        assertThrows(Compose.StaleEnvFileException.class, () -> compose.bootstrap(List.of("smp"), line -> {}));
    }

    @Test
    void recreateRefusesTooItIsWhatTheInterfacesRecreateButtonCalls() throws IOException {
        tempEnvFile = Files.createTempFile("env-file-", ".env");
        final Compose compose = new Compose(
                Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"), "nordtal-s2", path -> OptionalLong.of(0L));

        assertThrows(Compose.StaleEnvFileException.class, () -> compose.recreate("steward-ui", line -> {}));
    }
}
