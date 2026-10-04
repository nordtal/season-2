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
        FROZEN.put(
                "V10__steward_logs_in_as_its_own_role.sql",
                "be687eab3050c1c2a7cbabe85dfc31974e53ccb5c027dcd1aa13945625efd177");
        FROZEN.put(
                "V11__a_run_can_be_handed_to_a_one_shot.sql",
                "853ad5f3c3ce00d831b923ab75c9d3e6bacc47caf791df1418b7b6f0a04baa91");
        FROZEN.put(
                "V12__a_file_names_the_release_that_installed_it.sql",
                "be9d5bcdbec3681d1df30ea7f1d64d07898e447b2091bb414b7d4cf11288822f");
        FROZEN.put(
                "V13__steward_books_a_payment_in_one_transaction.sql",
                "ecadf24a178b9688313ef944dd24439be37125f989e166d62d7a901ffc28c8c8");
        FROZEN.put(
                "V14__a_journal_line_is_typed_values.sql",
                "58af9e2ad0f73c1f3f6f45653c6d324e706181663525a8920f771668b8c1fa1c");
        FROZEN.put(
                "V15__an_added_plugin_records_its_actor.sql",
                "76995605d1c802e7e7eacbfbf56337b658e82bf4dab8baf3e35426049f2a19e6");
        FROZEN.put(
                "V16__servers_publish_their_game_data.sql",
                "205a5fb469cd84081c3887bc0a8014b6ce87e80aa5cebacb4ffb92ace91590d5");
        FROZEN.put(
                "V17__message_overrides_live_in_the_database.sql",
                "a9289056b9c6988297d9ca91d2460ae061688eda7d46cd6ea59602c9ea534236");
        FROZEN.put(
                "V18__a_player_reads_in_a_language_and_zone_of_their_own.sql",
                "5582cf14c510c3a593a44a4fab8055a510d0a99e4d052071bebf0a15f1c49d19");
        FROZEN.put(
                "V19__a_server_is_never_asked_to_reload.sql",
                "fc25769b00b0ffd55762fd2282faf8e2ccdfb2e4bee52370222e970725a893a2");
        FROZEN.put(
                "V20__an_override_keeps_the_text_it_replaced.sql",
                "e45ee47a8231c142f97e7a9a13f17f4ac02be7b57927dac425f697a39681da12");
        FROZEN.put(
                "V21__a_journal_line_is_a_message.sql",
                "fa2fd47beea3188005e80602947d60a6bded4d1cca561bb9988e0551008c0b6d");
        FROZEN.put(
                "V22__an_alert_is_told_in_messages.sql",
                "6d8f2046172e14797c131a2e886e270e1f66658ac1703051b70b1baeedad7a98");
        FROZEN.put(
                "V23__prestige_is_the_networks.sql",
                "ae5301f4645517cdc1b7c18a523341e250ed7c2feb39f358287d18670d6b53bb");
        FROZEN.put(
                "V24__an_announcement_is_a_message.sql",
                "c2357b454d3ad88ee2ed6a7569224ad02a04f91da8c6639bbaddb92e9a3ce96b");
        FROZEN.put(
                "V25__an_admin_previews_a_text_where_it_is_shown.sql",
                "5ba3313e1a2a338cadfd283fde22794d3615e6c702836fdd96d6a46038cd84e2");
        FROZEN.put(
                "V26__a_run_report_is_told_in_messages.sql",
                "27ebdea354065693da714bc950b106218c173c155e71677a9d1fa5188fdf3adb");
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
