package eu.nordtal.s2.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Freezes every released migration byte for byte, since Flyway checksums the whole file, comments included.
 *
 * Adding a migration means adding a line here; changing a released one means a new migration.
 */
class MigrationsAreImmutableTest {

    /** SHA-256 of every file in {@code db/migration}, in version order. */
    private static final Map<String, String> FROZEN = new LinkedHashMap<>();

    static {
        FROZEN.put("V1__schema.sql", "718d3df7bbce1903b5d1cc82c83abb080504bc2db57f15053f5af797ea62a107");
        FROZEN.put(
                "V2__bot_reads_the_milestone_track.sql",
                "49cbf2fccb6d506c021acfb4dacecaf8d4153f951c5fd55b229d03999d9e0cf4");
        FROZEN.put(
                "V3__table_comments_name_steward.sql",
                "7d87a6342e09b1693c4822884f70a99ddf9cf1ba96377deb8b0fd83c8912ef96");
        FROZEN.put(
                "V4__the_run_inbox_is_stewards.sql",
                "2150325b77eaf1420c53d9f9d03a9db1d813f7a892872d7c529a60a870a0a6ad");
        FROZEN.put(
                "V5__settings_live_in_the_database.sql",
                "522be0eaa123914439838988afd2d34ae1198bce938f97733e48d200e5df9411");
        FROZEN.put(
                "V6__an_override_may_precede_its_group.sql",
                "8dd54db7d52b1a8be2d2efca09fe0bc47334329a6cfe5e39e73199ed3f7db1a6");
        FROZEN.put(
                "V7__the_allowlist_is_a_network_setting.sql",
                "0018fffe73e02297107a270a6c9a4af9f33e7c7a7c4424ce7d9e221261283f20");
        FROZEN.put("V8__alerts_have_one_path.sql", "e3e2a23c14bf175f74c576280932acfc489484010eaf10f7bb4173d9fc0d9d4c");
        FROZEN.put(
                "V9__every_kind_of_run_is_a_row.sql",
                "a687d46f12a09a569fbea4e2bce552d6d09db368a1fd3e9acdc0227d5beb589b");
    }

    @Test
    void noMigrationFileHasChangedSinceItWasFrozenHere() {
        final Path directory = migrations();
        assertAll(FROZEN.entrySet().stream().map(frozen -> () -> {
            final Path file = directory.resolve(frozen.getKey());
            assertTrue(
                    Files.isRegularFile(file),
                    frozen.getKey() + " is gone. A released migration"
                            + " is not deleted either - the database it ran against still has its row.");
            assertEquals(
                    frozen.getValue(),
                    sha256(file),
                    frozen.getKey() + " has changed. Flyway"
                            + " checksums comments too, so every deployment that already ran this file now"
                            + " refuses to start. Undo the edit; if the schema really has to change, that is"
                            + " a NEW migration.");
        }));
    }

    @Test
    void aNewMigrationIsFrozenInTheSameCommitThatAddsIt() {
        try (Stream<Path> files = Files.list(migrations())) {
            assertAll(files.map(Path::getFileName)
                    .map(Path::toString)
                    .sorted()
                    .map(name -> () -> assertTrue(
                            FROZEN.containsKey(name),
                            name + " is in db/migration and not in this"
                                    + " test. Add it with its hash - that line is what stops the next"
                                    + " search-and-replace from walking through it unnoticed.")));
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Returns the directory as the test classpath sees it, in this module's jar or in its resource directory. */
    private static Path migrations() {
        final var url = MigrationsAreImmutableTest.class.getResource("/db/migration");
        assertNotNull(url, "db/migration is not on the test classpath at all");
        try {
            final URI uri = url.toURI();
            if ("jar".equals(uri.getScheme())) {
                try {
                    FileSystems.getFileSystem(uri);
                } catch (final FileSystemNotFoundException notYetOpen) {
                    FileSystems.newFileSystem(uri, Map.of());
                }
            }
            return Path.of(uri);
        } catch (final IOException | URISyntaxException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String sha256(final Path file) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
