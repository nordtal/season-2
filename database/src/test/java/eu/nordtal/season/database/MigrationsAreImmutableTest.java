package eu.nordtal.season.database;

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
 * Freezes every released migration byte for byte, as Flyway checksums the whole file.
 * A new migration adds a line. A change to a released one needs a new migration.
 */
class MigrationsAreImmutableTest {

    /** The SHA-256 of every file in {@code db/migration}, in version order. */
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
        FROZEN.put(
                "V27__steward_s_old_role_holds_nothing.sql",
                "d6900e6f7cbb31b1e1d9e5392f138386af53fbcaf431c656c28b11659bc0ee7a");
        FROZEN.put(
                "V28__registration_is_the_bots_and_named_by_game.sql",
                "fb9850f0bc6d532131d64d99e2cce77b8e4b45cc4bd9f9ccfe8e4eb4676c35b4");
        FROZEN.put(
                "V29__the_bot_finds_its_roles_by_name.sql",
                "3de63a72c2ab0552d89ffae6e3d9a161a4fbd4374be2e35f3a3cc613f23ff507");
        FROZEN.put(
                "V30__servers_publish_their_command_trees.sql",
                "120f4e6b38f5a65c9b50ed73e489fb045d2d2dce1b3d5bda4dd8007bb9a3e16b");
        FROZEN.put(
                "V31__registration_has_one_shape.sql",
                "f38f4a96a902127c552dee1a16a8e350660b1d59be116bf1bc493d281c306fa0");
        FROZEN.put(
                "V32__only_steward_writes_settings.sql",
                "63e1642daaced7efad1283a27ac71b07912e4f5690a5a08514fd11734e781c72");
        FROZEN.put(
                "V33__the_bot_s_role_ids_are_its_own.sql",
                "42175449658e239ccc44d2281d18b34b2209db2a75df8e9f172682da599d4b99");
        FROZEN.put(
                "V34__a_session_row_keeps_the_hash_of_its_cookie.sql",
                "f6908deaacb44e5a7055da3f123955cd0ad1872e4421259d4d70184a6613816e");
        FROZEN.put(
                "V35__an_icon_sheet_records_the_painter_that_drew_it.sql",
                "bd8aedee7289e7cc579447b1642091386bf20350fd1db14e2264a0a533362f73");
        FROZEN.put(
                "V36__the_bot_publishes_the_guild_s_channels.sql",
                "a157c7b8853fc5161b70d62cd7b7b9b418fedf14b1eb141f337ba69756f7b3c9");
        FROZEN.put(
                "V37__a_run_s_notes_are_records.sql",
                "c900e8717a707b5f654cce80af1f53fbe2da6119991fd0dd1c8bb83b46748935");
        FROZEN.put(
                "V38__a_dead_resource_pack_is_an_alert.sql",
                "0401ccd70fbc8f3aedf111ebf417c11dcaf5caf6a93e3289f23e4e4f38255568");
        FROZEN.put(
                "V39__a_step_up_holds_as_long_as_it_was_granted.sql",
                "2c7f542ec54500ffd1c9bf0b9a14f07bc846fee391eec8aac8ab0b20816d2fc6");
        FROZEN.put(
                "V40__an_authenticator_app_is_a_second_factor.sql",
                "5bdb3dbd19c8b66d9d1f2bbecd018391a860ff3604a75ccf1de1bc37d6b72feb");
        FROZEN.put(
                "V41__a_journal_line_keeps_its_reference_and_days.sql",
                "62fa760cd0dfd61250f62edaca8469f09ee9a12d1a021df6974fc10a9183b216");
        FROZEN.put(
                "V42__the_bot_writes_journal_lines_of_its_own.sql",
                "11e48f2e97f9a46b8489b452e6219f81f71d5d4895cdadfccd686efc549824c8");
        FROZEN.put(
                "V43__a_failed_run_s_alert_is_titled_as_its_card_is.sql",
                "49ba27ce176a9086e1d71b9ed20e4d406763dce961b665006055f267bc919fe7");
        FROZEN.put(
                "V44__an_alert_keeps_what_it_measured.sql",
                "0040a2862118270df0ddb170b73fce9c48a94014f0bb1973c4313333a49be6cf");
        FROZEN.put(
                "V45__a_start_up_install_is_a_run.sql",
                "c69b0a1749a71a967c5f3e809e62e1c4cfc3f6c86b45ba059647be63194997f2");
        FROZEN.put(
                "V46__a_run_s_service_lines_keep_their_facts.sql",
                "7543db4e0f9ac671f3a836f1926a613fbe2a37e94420439f2a61156e3e97d46f");
        FROZEN.put(
                "V47__steward_s_old_role_is_gone.sql",
                "95df58f668b5d3ee9aaae0efaf7e3257e26a90d604cf655591f88c9738713639");
        FROZEN.put(
                "V48__a_tie_names_its_winner_as_a_member.sql",
                "b2739256a4b836d4f8d35900e5bb9705a35f3f5f8641eede9c009847d48c59ee");
        FROZEN.put(
                "V49__a_refused_tab_records_a_code_and_its_time.sql",
                "9934a646695d8b6dea8b98c374beedf3c94203aa2f50bc5f6d204b51738b71ca");
        FROZEN.put(
                "V50__an_aura_change_records_its_cause_as_typed_values.sql",
                "f23364d82220505b131983c64f4fcb63492ac5d79cd3d7b6ccc8bdb7ee571085");
        FROZEN.put(
                "V52__a_key_s_transports_and_a_browser_s_device_are_one_value_per_column.sql",
                "bedc1d35da3fa162419892b55d2d9db91f5625d46962343ccbcd1309f9a1b6f6");
        FROZEN.put(
                "V53__the_texts_an_override_replaced_are_a_row_each.sql",
                "8c03319bf7f74799c98d78cd991b58954a83ce77fd264389ee2632b8af1550b4");
        FROZEN.put(
                "V54__an_inbox_request_is_a_row_of_its_kind.sql",
                "25cd6783a521dd3f9394d3676be8a92ffc433ec8f4209e7f5c246e26d6da952c");
        FROZEN.put(
                "V55__the_bank_inbox_has_no_payload.sql",
                "77e74337ab65deed666f6cfb7261f3b2bc793997f12db438d4fff7ccf5d7fe27");
        FROZEN.put(
                "V60__the_journal_is_entries_with_typed_detail_rows.sql",
                "a884647a284a6b27c393eeb583966557097e1db7d178a443bb7db283e4332343");
        FROZEN.put(
                "V70__a_run_s_steps_and_facts_are_rows.sql",
                "d9b616c9b269fe9dce81d54e86fa9f6d5fca70459a4d3dedeba7394e374f90e0");
        FROZEN.put(
                "V71__a_discord_layout_is_stored_as_an_editor_rebuilt_it.sql",
                "8c3cc78c3ff1d82c64fb3814819406cde554f0da76a91eb7b19698f4e555631b");
        FROZEN.put(
                "V72__an_admins_discord_document_is_stored_whole.sql",
                "a992caab55d4592394ecbeb49848865bcd88d126fbcec738b3b656a56bf481b5");
        FROZEN.put(
                "V73__a_server_tries_a_settings_change_before_it_is_saved.sql",
                "09d953945d0ec6508f4152d6fbe4b2454d4691c37cd778808fa496dd96175ff1");
        FROZEN.put(
                "V74__the_rebuilt_layout_tables_are_gone.sql",
                "f89028916eff562514fe77a7d476bb5210739361b7603dbfad8c99fa8fe9a002");
        FROZEN.put(
                "V75__a_player_keeps_a_survival_and_a_creative_state_per_server.sql",
                "b43a18e0bb61b9ad80f3b00ae3099807eba7e0663283cc14b0e6d4babb242c4d");
        FROZEN.put(
                "V76__every_save_of_a_document_is_kept_as_a_version.sql",
                "c9010d38696bdb71c2918ad9db819ed80f58ca0f0b0fadb1ec3c42ef08b157a8");
        FROZEN.put(
                "V77__a_shared_part_is_a_part_documents_copy.sql",
                "2a5edf98cc3c8182ca5230ff358dd99b37604701a82a74f80ac996d9cc76805d");
        FROZEN.put(
                "V78__an_overrides_hash_comes_from_the_texts_it_replaced.sql",
                "4de798f4c95dc7110ec9437322a1c4135afe56a22c9d63b5639ae5a48d9de310");
        FROZEN.put(
                "V79__backup_archives_are_a_catalogue_and_a_failed_tool_keeps_its_exit_code.sql",
                "a1f8fa9f3557291a2e2f05df9b196f42a111d91b341ad30f825a763e4181e32d");
        FROZEN.put(
                "V80__an_admin_sets_aura_and_spins_in_steward.sql",
                "6231ac3ad2b0bc99f02db849a12e20818af79ed36afdd2464c26a6e6f6016a13");
        FROZEN.put(
                "V81__an_alert_is_a_journal_entry_with_a_level_and_measures.sql",
                "96f05e520b443d4aef9a5d14d57fb85514933e5b725954c76c81537d24e5b8f8");
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

    /** Returns the migration directory as the test classpath sees it. */
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
