package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding the config files under the mount, one directory per compose service. */
class ConfigFilesDiscoverTest {

    @TempDir
    Path root;

    /** Somewhere a link can point that the mount does not contain. */
    @TempDir
    Path outside;

    @Test
    void everyYmlIsFoundAndSortedByServiceThenName() throws IOException {
        write("steward-worker/steward.yml");
        write("steward-worker/database.yml");
        write("smp/nordtal-smp/config.yml");
        write("smp/display-tags/config.yml");
        write("discord-bot/config.yml");

        final List<ConfigLocation> found = ConfigFiles.discover(root);

        assertEquals(
                List.of(
                        "discord-bot/config.yml",
                        "smp/display-tags/config.yml",
                        "smp/nordtal-smp/config.yml",
                        "steward-worker/database.yml",
                        "steward-worker/steward.yml"),
                found.stream().map(l -> l.service() + "/" + l.name()).toList());
    }

    @Test
    void theServiceIsTheDirectoryAndTheNameIsEverythingUnderIt() throws IOException {
        write("smp/nordtal-smp/config.yml");

        final ConfigLocation location = ConfigFiles.discover(root).getFirst();

        assertEquals("smp", location.service());
        assertEquals("nordtal-smp/config.yml", location.name());
        assertEquals(root.resolve("smp/nordtal-smp/config.yml"), location.file());
        assertTrue(location.writable());
    }

    @Test
    void aBinaryFileIsIgnoredButOtherTextFormatsAreNotAnyMore() throws IOException {
        // The mount holds files like README.txt, spark/config.json and voicechat-server.properties, real content.
        write("smp/config.yml");
        Files.writeString(root.resolve("smp/server.properties"), "level=5\n");
        Files.writeString(root.resolve("smp/config.yaml"), "port: 1\n");
        Files.writeString(root.resolve("smp/ops.json"), "{}\n");
        Files.write(root.resolve("smp/world.dat"), new byte[] {0x1f, (byte) 0x8b, 0, 1, 2, 3});

        assertEquals(
                List.of("config.yaml", "config.yml", "ops.json", "server.properties"),
                ConfigFiles.discover(root).stream()
                        .map(ConfigLocation::name)
                        .sorted()
                        .toList());
    }

    @Test
    void jcoresOwnBakIsNotOfferedAsAFileToEdit() throws IOException {
        // jcore writes gate.yml and leaves the old content in gate.yml.bak; the backup is YAML and must not be listed.
        write("proxy/gate.yml");
        Files.writeString(root.resolve("proxy/gate.yml.bak"), "server-limbo: old\n");

        assertEquals(
                List.of("gate.yml"),
                ConfigFiles.discover(root).stream()
                        .map(ConfigLocation::name)
                        .sorted()
                        .toList());
    }

    @Test
    void theEnvironmentOverrideMarkerIsNotOfferedAsAFileToEdit() throws IOException {
        // Every service writes <name>.env-overrides.txt beside its config; it must not be listed as one of its own.
        write("discord-bot/access.yml");
        Files.writeString(root.resolve("discord-bot/access.env-overrides.txt"), "languages\nroles.access\n");
        // The half-written one: EnvOverrideFile.write moves a .tmp into place, and a walk can land inside that window.
        Files.writeString(root.resolve("discord-bot/access.env-overrides.txt.tmp"), "languages\n");

        assertEquals(
                List.of("access.yml"),
                ConfigFiles.discover(root).stream()
                        .map(ConfigLocation::name)
                        .sorted()
                        .toList());
    }

    @Test
    void savedTranslationsAreAMessageBundleNotAConfigFile() throws IOException {
        // The same directory MessageBundles.discover turns into a bundle must not also be listed here as text.
        write("smp/config.yml");
        Files.createDirectories(root.resolve("smp/messages"));
        Files.writeString(root.resolve("smp/messages/de_DE.properties"), "a=b\n");
        Files.writeString(root.resolve("smp/messages/README.md"), "overrides\n");
        Files.createDirectories(root.resolve("smp/messagesx"));
        Files.writeString(root.resolve("smp/messagesx/config.yml"), "a: 1\n");

        assertEquals(
                List.of("config.yml", "messagesx/config.yml"),
                ConfigFiles.discover(root).stream()
                        .map(ConfigLocation::name)
                        .sorted()
                        .toList());
    }

    @Test
    void aTmpDirectoryIsScratchAndAFileMerelyCalledTmplIsNot() throws IOException {
        // spark keeps profiler dumps under spark/tmp, excluded by whole path segment rather than by substring.
        write("smp/config.yml");
        Files.createDirectories(root.resolve("smp/spark/tmp"));
        Files.writeString(root.resolve("smp/spark/tmp/about.txt"), "spark\n");
        Files.createDirectories(root.resolve("smp/tmpl"));
        Files.writeString(root.resolve("smp/tmpl/config.yml"), "a: 1\n");

        assertEquals(
                List.of("config.yml", "tmpl/config.yml"),
                ConfigFiles.discover(root).stream()
                        .map(ConfigLocation::name)
                        .sorted()
                        .toList());
    }

    @Test
    void aFileLyingDirectlyInTheRootIsListedWithNoService() throws IOException {
        write("loose.yml");

        final ConfigLocation location = ConfigFiles.discover(root).getFirst();

        assertEquals("", location.service());
        assertEquals("loose.yml", location.name());
    }

    @Test
    void aRootThatIsNotThereIsAnEmptyListAndNotAFailure() {
        assertEquals(List.of(), ConfigFiles.discover(root.resolve("never-mounted")));
    }

    @Test
    void anUnreadableFileIsStillFoundAndSaysSo() throws IOException {
        // A file this process may not open has nothing to show; discovery answers readable so the row draws dead.
        final Path service = Files.createDirectories(root.resolve("locked"));
        final Path file = Files.writeString(service.resolve("config.yml"), "port: 8080\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w--w----"));
        try {
            // Root ignores the permission bits, and this project's containers run as root: nothing to assert here.
            Assumptions.assumeFalse(Files.isReadable(file), "running as a user that can read a file with no read bit");

            final ConfigLocation found = ConfigFiles.discover(root).getFirst();
            assertFalse(found.readable(), "the file has no read bit for this user");
            assertEquals("config.yml", found.name());
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            Files.deleteIfExists(file);
        }
    }

    @Test
    void anEmptyRootIsAnEmptyList() {
        assertEquals(List.of(), ConfigFiles.discover(root));
    }

    @Test
    void aFileInAReadOnlyDirectoryIsNotWritable() throws IOException {
        final Path service = Files.createDirectories(root.resolve("readonly"));
        final Path file = Files.writeString(service.resolve("config.yml"), "port: 8080\n");
        Files.setPosixFilePermissions(service, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            // Root ignores the permission bits, and this project's containers run as root: nothing to assert here.
            Assumptions.assumeFalse(
                    Files.isWritable(service), "running as a user that can write a read-only directory");

            assertFalse(
                    ConfigFiles.discover(root).getFirst().writable(),
                    "the write is a rename into the directory, so the directory has to be writable");
        } finally {
            Files.setPosixFilePermissions(service, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.deleteIfExists(file);
        }
    }

    /**
     * A symbolic link is not a config file, however much its name ends in {@code .yml}.
     *
     * A link would carry the read and the save outside the mount, so the list refuses it.
     */
    @Test
    void aSymbolicLinkOutOfTheMountIsNotListed() throws IOException {
        Assumptions.assumeTrue(canLink(), "this filesystem does not do symbolic links");
        final Path secret = Files.writeString(outside.resolve("secret.yml"), "token: hunter2\n");
        write("smp/real.yml");
        Files.createSymbolicLink(root.resolve("smp/escape.yml"), secret);

        assertEquals(
                List.of("real.yml"),
                ConfigFiles.discover(root).stream().map(ConfigLocation::name).toList());
    }

    /** The same, for a link that stays inside the mount: one file, listed once, under its own name. */
    @Test
    void aSymbolicLinkInsideTheMountIsNotListedEither() throws IOException {
        Assumptions.assumeTrue(canLink(), "this filesystem does not do symbolic links");
        write("smp/real.yml");
        Files.createSymbolicLink(root.resolve("smp/also.yml"), root.resolve("smp/real.yml"));

        assertEquals(
                List.of("real.yml"),
                ConfigFiles.discover(root).stream().map(ConfigLocation::name).toList());
    }

    private boolean canLink() {
        try {
            final Path probe = outside.resolve("probe");
            Files.createSymbolicLink(probe, outside);
            Files.delete(probe);
            return true;
        } catch (final IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private void write(final String relative) throws IOException {
        final Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "port: 8080\n");
    }
}
