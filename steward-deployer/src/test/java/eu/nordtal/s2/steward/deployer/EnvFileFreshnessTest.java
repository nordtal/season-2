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
 * A rotated env file behind a stale file bind mount is refused on its zero link count, and nothing else.
 *
 * The link count is faked, since a real orphaned inode needs a real mount.
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
        // No fake: the default LinkCounter must read an ordinary file as healthy too.
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

        assertThrows(Compose.StaleEnvFileException.class, () -> compose.recreate("steward", line -> {}));
    }
}
