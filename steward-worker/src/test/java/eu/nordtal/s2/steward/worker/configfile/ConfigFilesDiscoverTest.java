package eu.nordtal.s2.steward.worker.configfile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding the config files under the mount.
 *
 * <p>The layout is one directory per compose service, and inside it whatever that service's volume
 * happens to contain - a single {@code steward.yml} for the worker, a whole
 * {@code plugins/<name>/config.yml} tree for a Minecraft server.</p>
 */
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

        assertEquals(List.of(
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
        // Pre-steward/55 this asserted the opposite: only `.yml` survived. The mount actually holds
        // a `README.txt`, a `spark/config.json`, a `voicechat-server.properties` - real files this
        // page could not show for no reason but their extension. Only a binary format is still
        // excluded, and by content now, not by name.
        write("smp/config.yml");
        Files.writeString(root.resolve("smp/server.properties"), "level=5\n");
        Files.writeString(root.resolve("smp/config.yaml"), "port: 1\n");
        Files.writeString(root.resolve("smp/ops.json"), "{}\n");
        Files.write(root.resolve("smp/world.dat"), new byte[] {0x1f, (byte) 0x8b, 0, 1, 2, 3});

        assertEquals(List.of("config.yaml", "config.yml", "ops.json", "server.properties"),
                ConfigFiles.discover(root).stream().map(ConfigLocation::name).sorted().toList());
    }

    @Test
    @DisplayName("jcore's own .bak is not offered as a file to edit")
    void aBackupIsNotAConfigFile() throws IOException {
        // The one new entry the broadening above would get actively wrong. jcore writes `gate.yml`
        // and leaves the previous content in `gate.yml.bak`; the backup is YAML, it parses, and it
        // would draw an ordinary form whose every control writes to a file nothing reads. Measured
        // on the running mount on 2026-09-16 there were six of them. Till: leave them out.
        write("network-control/gate.yml");
        Files.writeString(root.resolve("network-control/gate.yml.bak"), "server-limbo: old\n");

        assertEquals(List.of("gate.yml"),
                ConfigFiles.discover(root).stream().map(ConfigLocation::name).sorted().toList());
    }

    @Test
    @DisplayName("the environment-override marker is not offered as a file to edit (steward/76)")
    void anEnvironmentOverrideMarkerIsNotAConfigFile() throws IOException {
        // The same trap the .bak above names, set off by a file steward/76 newly invented: every
        // service now writes `<name>.env-overrides.txt` beside its own config, it is plain text so
        // `isProbablyText` says yes, and without the filter it would be listed on the service page
        // as a configuration of its own - opened and editable. Editing it would change what the
        // warning says without changing one thing about what the environment actually overrides,
        // which is worse than merely confusing.
        write("discord-bot/access.yml");
        Files.writeString(root.resolve("discord-bot/access.env-overrides.txt"), "languages\nroles.access\n");
        // And the half-written one: `EnvOverrideFile.write` moves a `.tmp` into place, and a walk
        // can land inside that window.
        Files.writeString(root.resolve("discord-bot/access.env-overrides.txt.tmp"), "languages\n");

        assertEquals(List.of("access.yml"),
                ConfigFiles.discover(root).stream().map(ConfigLocation::name).sorted().toList());
    }

    @Test
    @DisplayName("a tmp directory is scratch, and a file merely called tmpl is not")
    void scratchDirectoriesAreNotWalkedButASimilarNameIs() throws IOException {
        // spark keeps profiler dumps and an about.txt under `spark/tmp`. Excluded by whole path
        // segment rather than by substring, which is what keeps the second file below listed.
        write("smp/config.yml");
        Files.createDirectories(root.resolve("smp/spark/tmp"));
        Files.writeString(root.resolve("smp/spark/tmp/about.txt"), "spark\n");
        Files.createDirectories(root.resolve("smp/tmpl"));
        Files.writeString(root.resolve("smp/tmpl/config.yml"), "a: 1\n");

        assertEquals(List.of("config.yml", "tmpl/config.yml"),
                ConfigFiles.discover(root).stream().map(ConfigLocation::name).sorted().toList());
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
        // The listing has to carry this, not the click: a file this process may not open has
        // nothing to show, and finding that out by tapping it and reading a red alert is one step
        // too late. So discovery answers `readable` and the row is drawn dead before anybody tries.
        final Path service = Files.createDirectories(root.resolve("locked"));
        final Path file = Files.writeString(service.resolve("config.yml"), "port: 8080\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w--w----"));
        try {
            // Same reason as the read-only directory below: root ignores the bits, and this
            // project's containers run as root. A skip is worth more than a green test that
            // asserted nothing.
            Assumptions.assumeFalse(Files.isReadable(file),
                    "running as a user that can read a file with no read bit");

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
            // root ignores the permission bits, and this project's containers run as root. Then
            // there is nothing to assert here and saying so is better than a green test that
            // checked nothing.
            Assumptions.assumeFalse(Files.isWritable(service),
                    "running as a user that can write a read-only directory");

            assertFalse(ConfigFiles.discover(root).getFirst().writable(),
                    "the write is a rename into the directory, so the directory has to be writable");
        } finally {
            Files.setPosixFilePermissions(service, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.deleteIfExists(file);
        }
    }

    /**
     * A symbolic link is not a config file, however much its name ends in {@code .yml}.
     *
     * <p>{@link ConfigFiles} says of itself that a file is found by matching and never by joining,
     * and that this is what makes {@code ../../etc/shadow} a 404 rather than a question about
     * decoding. A link defeats exactly that claim from the other end: {@code Files.isRegularFile}
     * follows it, so the discovered {@link ConfigLocation} points wherever the link does and both
     * the read and the save cross the mount. Whoever can drop a file into a shared config volume
     * can drop a link into it, so the boundary has to be checked where the list is made.</p>
     */
    @Test
    void aSymbolicLinkOutOfTheMountIsNotListed() throws IOException {
        Assumptions.assumeTrue(canLink(), "this filesystem does not do symbolic links");
        final Path secret = Files.writeString(outside.resolve("secret.yml"), "token: hunter2\n");
        write("smp/real.yml");
        Files.createSymbolicLink(root.resolve("smp/escape.yml"), secret);

        assertEquals(List.of("real.yml"), ConfigFiles.discover(root).stream()
                .map(ConfigLocation::name).toList());
    }

    /** The same, for a link that stays inside the mount: one file, listed once, under its own name. */
    @Test
    void aSymbolicLinkInsideTheMountIsNotListedEither() throws IOException {
        Assumptions.assumeTrue(canLink(), "this filesystem does not do symbolic links");
        write("smp/real.yml");
        Files.createSymbolicLink(root.resolve("smp/also.yml"), root.resolve("smp/real.yml"));

        assertEquals(List.of("real.yml"), ConfigFiles.discover(root).stream()
                .map(ConfigLocation::name).toList());
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
