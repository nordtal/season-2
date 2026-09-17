package eu.nordtal.s2.steward.deployer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * steward/102: a FILE bind mount follows the inode, not the path, so a host rotation after the
 * container started leaves this process reading a deleted file forever, silently. The one signal
 * that survives the mount is the orphaned inode's own link count, which drops to zero - this class
 * is what makes {@link Compose#assertEnvFileFresh()} refuse on that signal, and never anything else.
 *
 * <p>These tests need no Docker daemon and no real bind mount, on purpose, the same way
 * {@link ComposeRefusesItselfTest} needs none: a real orphaned inode only exists behind an actual
 * mount (steward/102 reproduced one by hand, in a throwaway container, to prove the mechanism before
 * this fix), and faking the link count is what lets the refusal itself be exercised in a plain JVM.
 * A real file on disk, with its real (healthy) link count, is used for the "nothing wrong" case so
 * the fake is only ever standing in for the one number this class cannot otherwise get to zero.</p>
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
    @DisplayName("a link count of zero is refused, and the message points at steward/102")
    void refusesAnOrphanedInode() throws IOException {
        tempEnvFile = Files.createTempFile("steward-102-", ".env");
        Compose compose = new Compose(Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"),
                "nordtal-s2", path -> OptionalLong.of(0L));

        IOException refused = assertThrows(Compose.StaleEnvFileException.class,
                compose::assertEnvFileFresh);

        assertTrue(refused.getMessage().contains("steward/102"), refused.getMessage());
        assertTrue(refused.getMessage().contains("deleted inode"), refused.getMessage());
    }

    @Test
    @DisplayName("a healthy link count is not refused")
    void aFreshFileIsFine() throws IOException {
        tempEnvFile = Files.createTempFile("steward-102-", ".env");
        Compose compose = new Compose(Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"),
                "nordtal-s2", path -> OptionalLong.of(1L));

        assertDoesNotThrow(compose::assertEnvFileFresh);
    }

    @Test
    @DisplayName("the real, POSIX-backed link count of an ordinary file is not zero")
    void theRealLinkCounterAgreesOnAnOrdinaryFile() throws IOException {
        // No fake here: this is the constructor steward-deployer actually runs with in production,
        // proving the default `LinkCounter` this class wires in reads a real, ordinary file as
        // healthy - not just that a fake saying "1" is accepted.
        tempEnvFile = Files.createTempFile("steward-102-", ".env");
        Files.writeString(tempEnvFile, "DEMO_VALUE=before\n");
        Compose compose = new Compose(Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"),
                "nordtal-s2");

        assertDoesNotThrow(compose::assertEnvFileFresh);
    }

    @Test
    @DisplayName("a missing file is not this check's problem - base() already leaves it out")
    void aMissingFileIsNotChecked() {
        Compose compose = new Compose(Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"),
                Path.of("/app"), "nordtal-s2", path -> {
                    throw new AssertionError("the link counter must not even be asked about a path"
                            + " that does not exist - there is nothing orphaned to detect");
                });

        assertDoesNotThrow(compose::assertEnvFileFresh);
    }

    @Test
    @DisplayName("up() refuses before it ever builds a command line, let alone runs one")
    void upRefusesAnOrphanedInodeBeforeTouchingDocker() throws IOException {
        tempEnvFile = Files.createTempFile("steward-102-", ".env");
        Compose compose = new Compose(Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"),
                "nordtal-s2", path -> OptionalLong.of(0L));

        assertThrows(Compose.StaleEnvFileException.class,
                () -> compose.up(List.of("smp"), line -> { }));
    }

    @Test
    @DisplayName("bootstrap() refuses too - it is the path deployer up takes")
    void bootstrapRefusesAnOrphanedInode() throws IOException {
        tempEnvFile = Files.createTempFile("steward-102-", ".env");
        Compose compose = new Compose(Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"),
                "nordtal-s2", path -> OptionalLong.of(0L));

        assertThrows(Compose.StaleEnvFileException.class,
                () -> compose.bootstrap(List.of("smp"), line -> { }));
    }

    @Test
    @DisplayName("recreate() refuses too - it is what the interface's recreate button calls")
    void recreateRefusesAnOrphanedInode() throws IOException {
        tempEnvFile = Files.createTempFile("steward-102-", ".env");
        Compose compose = new Compose(Path.of("/app/compose.yml"), tempEnvFile, Path.of("/app"),
                "nordtal-s2", path -> OptionalLong.of(0L));

        assertThrows(Compose.StaleEnvFileException.class,
                () -> compose.recreate("steward-ui", line -> { }));
    }
}
