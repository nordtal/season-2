package eu.nordtal.s2.common.db;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
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
 * Every migration that has been released is frozen, byte for byte, and this file is the freezer.
 *
 * <b>Flyway checksums the whole file, comments included.</b> A migration that has run is recorded
 * in {@code flyway_schema_history} with that number, and on the next start every consumer compares
 * it: the worker before it migrates, the bot in {@code SchemaCheck.validate}. When they disagree the
 * bot does not start. It does not degrade, it does not warn - it refuses the database and exits, and
 * so does an update run.
 *
 * <b>This is not hypothetical; it is why the test exists.</b> The rename of {@code updater} to
 * {@code steward-worker} swept through the repository and changed one sentence of a comment inside
 * {@code V12__update_is_one_run.sql}, a migration v0.8.6 had already shipped. Nothing failed to
 * compile, no test went red, and every review of that commit read it as a rename. It surfaced weeks
 * later on a deployment, as {@code Migration checksum mismatch for migration version 12} out of a
 * bot that would not come up - and a search-and-replace is precisely the kind of change nobody
 * thinks to check a migration for.
 *
 * <b>Adding a migration means adding a line here.</b> That is the deliberate cost and it is one
 * line: the map is not generated, because a generated map records whatever the file says today and
 * would have recorded the broken V12 just as happily. Editing an existing line is the thing this
 * test is here to stop - if a released migration really has to change, the answer is a new
 * migration that alters what the old one created, never a corrected copy of the old one.
 */
class MigrationsAreImmutableTest {

    /**
     * SHA-256 of every file in {@code db/migration}, in version order.
     *
     * SHA-256 rather than Flyway's own CRC32: the point is "this file has not changed", which any
     * hash answers, and Flyway's number comes out of an internal class whose signature is not API.
     * A mismatch here is a mismatch there for the same reason either way - the bytes moved.
     */
    private static final Map<String, String> FROZEN = new LinkedHashMap<>();

    static {
        FROZEN.put("V1__access.sql", "53c2516f8206871f36b0e3680fae059b5426c79db9866f795691d68cd0808aca");
        FROZEN.put("V2__bot_state.sql", "eda34a10983f732466563eaf74ba53a5cfeb40a2822c9a72ffcd2202bf7ff162");
        FROZEN.put("V3__bot_setting.sql", "b60034927576ec2325b221db529cce25ee13b3514e5d43c89a7db0c8d081d79a");
        FROZEN.put("V4__phase_admin_playtime.sql", "3c6daf3d8def2ab21a1438bf16febdf5de3e7cd9699d7e2912fe9a480d8fbe89");
        FROZEN.put("V5__hunger_games.sql", "4a761fc45a184b1bdfdb3588df52c02c082ca793ca23b09b3993b48cadaca592");
        FROZEN.put("V6__smp.sql", "1902184489ae2bf636f7d7f49adf34700ee2f5927f073ab1cc2af7b14c36919a");
        FROZEN.put("V7__update_request.sql", "e0c6601d20af7289e58051504d66a2b12517493cd459bc3443d51b00d47da5c6");
        FROZEN.put("V8__pre_launch.sql", "e404fb391076150d67adbd7b4fdea8395e4af4c8aab4e3f22b4ba6f81efd3790");
        FROZEN.put("V9__smp_start.sql", "cfaf9c77a23da1313909c2c01400392e48fdf2c4281d89be800d4214f9243001");
        FROZEN.put("V10__drop_smp_duel.sql", "62e495ae17002bb76c8d69022ca73ac55f0823031a489d6b7a864198bad24f2b");
        FROZEN.put("V11__command_request.sql", "51ea000390f8d8a2f71582fe3f653d3c892f3bf97dff41e065870aa1faa223ba");
        FROZEN.put("V12__update_is_one_run.sql", "467dc7039be42f24f53483dc1d3a6ac66717ef1df3ef1c551bfd065fced2a8b0");
        FROZEN.put(
                "V13__countdown_after_resolving.sql",
                "050e49837ebcddf49939c5f67e35156c07962b58f354983c63f01a8b2e88317b");
        FROZEN.put("V14__backup_request.sql", "875698e677bdf1fbab3d0f4f886a279b5fd9339d307ea9304a9a86e52ad0468f");
        FROZEN.put("V15__network_setting.sql", "0c8b5cb6f817b37e354607ddef0ccc92346fcdbeb1650e98a428b3086545b3ad");
        FROZEN.put("V16__smp_welcome.sql", "c19fdef6628c0b28338e48d998758c3f904a200442877564307c616049e2f14f");
        FROZEN.put("V17__metric_sample.sql", "7b6eee77bf978de455c98a96945068875bad57698a2669001d13a79921b4cd0e");
        FROZEN.put("V18__command_request_web.sql", "f2a203a69252181743fba7bdb59261d7ded361f66431fa9aa3c8a838c53b2906");
        FROZEN.put("V19__steward_session.sql", "7a8ea086b6e364501828119da501fb14e3a8fccd0ef473efbcf8b45d277f7783");
        FROZEN.put("V20__steward_credential.sql", "db65081934220ecbbf227acb602a7a060ce191a337549e7fdbb8748da25cf701");
        FROZEN.put(
                "V21__discord_and_minecraft_profile_cache.sql",
                "8ca83e7171a68ba9a8bd806c11494c03aa4153f7be7de58b21f2c9b24661157a");
        FROZEN.put("V22__online_count.sql", "e25e88ca2691946fe8acec2cebb70bb08ab1996abc128a9eb11c4eab1f660efa");
        FROZEN.put("V23__payment_request_seam.sql", "a1be6131fb0c3a34eeeeb99040bf132f56b655a9c05afbd58e30b7789b594990");
        FROZEN.put("V24__online_player.sql", "d524af0eaef269de4d40bfc4f7ecc4fd947dd6cb825064818e06a7b60849dfa5");
        FROZEN.put(
                "V25__payment_notice_posted.sql", "d112cb771b54200bb9250386728831586808db9003771a7fa6882ebb5a15371a");
        FROZEN.put(
                "V26__web_push_subscription.sql", "1874827fb014fd33528d0c5563e8ffe3f55aa20cbf87fa12c60a929a5a8e798e");
        FROZEN.put("V27__update_request_scope.sql", "4d1a04b3c99b75bbe59e25fed7192beef572f7392713a8f16ffa81a148f9d373");
        FROZEN.put("V28__service_hold.sql", "75d66783c441e0cd7eb5c47885c94c15f07c6acc1749d1acc0af8a3dcf9b8719");
        FROZEN.put("V29__service_plugin.sql", "b7ec4c8bb129b99bc0e042d68615db2f8d78e1577a1acc1f39a0605e35de23ee");
        FROZEN.put(
                "V30__push_preferences_and_devices.sql",
                "85a9de8b6186c76c32dfe2d2dfe8ca2920fe8d66129f93b4a94b081518bce31d");
        FROZEN.put("V31__proxy_swap.sql", "10c39944cec5fee2e103e94b16ae0a3e973ade56e6625ba6a23e10e05444a464");
        FROZEN.put(
                "V33__access_request_reload.sql", "fee93fb6fd3504b583f604aab46451dd8ccd228f088724edfb7128577dfad280");
        FROZEN.put("V32__access_request.sql", "9de3226481b89ab0d9f24cbc4c6188fd0388c0d0e50320fb069dacac2990afdb");
        FROZEN.put("V34__admin_tree.sql", "b0fdb96280a85020d604eb435b9053ecfc34229357ed3532024ae2d65e04d7f0");
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

    /**
     * The directory as the test classpath sees it, which is the copy the jar would carry.
     *
     * Reached through the classpath rather than through {@code RepositoryRoot} on purpose: these
     * files are this module's own resources, so the classpath answer is both the one Flyway reads at
     * runtime and a dependency Gradle already tracks - no {@code repositoryRootTestInputs} entry to
     * forget, and no way for the test to stay UP-TO-DATE across an edit.
     */
    private static Path migrations() {
        final var url = MigrationsAreImmutableTest.class.getResource("/db/migration");
        assertNotNull(url, "db/migration is not on the test classpath at all");
        try {
            return Path.of(url.toURI());
        } catch (final URISyntaxException impossible) {
            throw new IllegalStateException(impossible);
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
