package eu.nordtal.s2.common.access;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The admin tree against a real PostgreSQL running the real migrations: the bootstrap, who may
 * revoke whom, the branch that goes with a revocation, the limit on grants, and the constraints
 * that keep anything but this tree from making an admin.
 *
 * <p>Driven by hand from {@link BeforeAll}, as every other integration test in this module, and
 * skipped when no Docker daemon is reachable.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminTreeIntegrationTest {

    private static final String ROOT = "500000000000000001";
    private static final String A = "500000000000000002";
    private static final String B = "500000000000000003";
    private static final String A1 = "500000000000000004";
    private static final String A1X = "500000000000000005";
    private static final String OUTSIDER = "500000000000000009";

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private AdminTree tree;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed admin tree tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        AccessSchema.migrate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        dataSource = null;
    }

    @BeforeEach
    void emptyTree() {
        execute("TRUNCATE TABLE admin_grant, discord_user CASCADE");
        for (final String member : List.of(ROOT, A, B, A1, A1X, OUTSIDER)) {
            execute("INSERT INTO discord_user (discord_id) VALUES ('" + member + "')");
        }
        tree = AdminTree.using(dataSource);
    }

    // ---------------------------------------------------------------- the bootstrap

    @Test
    @DisplayName("the first to claim an empty tree is its root, and nobody after them")
    void theFirstClaimIsTheRoot() {
        assertTrue(tree.claimRootIfNobody(ROOT));
        assertFalse(tree.claimRootIfNobody(A), "a second claim would be a second root");

        assertTrue(tree.isAdmin(ROOT));
        assertFalse(tree.isAdmin(A));
        assertEquals(List.of(new AdminTree.Admin(ROOT, null, tree.admins().getFirst().grantedAt())),
                tree.admins());
    }

    @Test
    @DisplayName("the claim creates the row when the bot has not written one yet")
    void theClaimCreatesTheRow() {
        assertTrue(tree.claimRootIfNobody("500000000000000077"));
        assertTrue(tree.isAdmin("500000000000000077"));
    }

    @Test
    @DisplayName("once the root is gone with nobody under them, the next sign-in claims again")
    void anEmptyTreeCanBeClaimedAgain() {
        tree.claimRootIfNobody(ROOT);
        tree.dropWithBranch(ROOT);

        assertTrue(tree.claimRootIfNobody(A));
    }

    // ---------------------------------------------------------------- granting

    @Test
    @DisplayName("a grant records who granted, and only an admin may grant")
    void aGrantRecordsTheGranter() {
        tree.claimRootIfNobody(ROOT);

        assertEquals(AdminTree.Grant.ACTOR_NOT_ADMIN, tree.grant(A, B));
        assertEquals(AdminTree.Grant.GRANTED, tree.grant(ROOT, A));
        assertEquals(AdminTree.Grant.ALREADY_ADMIN, tree.grant(ROOT, A));
        assertEquals(AdminTree.Grant.GRANTED, tree.grant(A, A1));

        final List<AdminTree.Admin> admins = tree.admins();
        assertEquals(List.of(ROOT, A, A1), admins.stream().map(AdminTree.Admin::discordId).toList());
        assertNull(admins.get(0).grantedBy());
        assertEquals(ROOT, admins.get(1).grantedBy());
        assertEquals(A, admins.get(2).grantedBy());
    }

    @Test
    @DisplayName("nobody who is not in the guild can be made an admin")
    void onlyAMemberCanBeGranted() {
        tree.claimRootIfNobody(ROOT);
        execute("UPDATE discord_user SET member_state = 'LEFT' WHERE discord_id = '" + A + "'");
        execute("UPDATE discord_user SET member_state = 'BANNED' WHERE discord_id = '" + B + "'");

        assertEquals(AdminTree.Grant.NOT_A_MEMBER, tree.grant(ROOT, A));
        assertEquals(AdminTree.Grant.NOT_A_MEMBER, tree.grant(ROOT, B));
        assertEquals(AdminTree.Grant.NOT_A_MEMBER, tree.grant(ROOT, "500000000000000088"),
                "an account the bot has never seen is not a member either");
        assertEquals(0, count("SELECT count(*) FROM admin_grant"), "a refused grant is not counted");
    }

    @Test
    @DisplayName("three grants an hour across all admins together, and the fourth is refused at once")
    void threeGrantsAnHour() {
        tree.claimRootIfNobody(ROOT);
        assertEquals(AdminTree.Grant.GRANTED, tree.grant(ROOT, A));
        assertEquals(AdminTree.Grant.GRANTED, tree.grant(A, A1));
        assertEquals(AdminTree.Grant.GRANTED, tree.grant(ROOT, B));

        assertEquals(AdminTree.Grant.RATE_LIMITED, tree.grant(A1, A1X),
                "the limit is global - A1 has granted nobody and is still refused");
        assertFalse(tree.isAdmin(A1X));

        // A revoked grant still counted: revoking and re-granting is not a way round the limit.
        tree.revoke(ROOT, B);
        assertEquals(AdminTree.Grant.RATE_LIMITED, tree.grant(ROOT, B));

        execute("UPDATE admin_grant SET granted = granted - interval '61 minutes'");
        assertEquals(AdminTree.Grant.GRANTED, tree.grant(A1, A1X), "an hour later it is open again");
    }

    // ---------------------------------------------------------------- revoking

    @Test
    @DisplayName("an admin revokes only strictly below them, and the branch goes with the target")
    void revokingIsDownwardAndTakesTheBranch() {
        buildTree();

        assertEquals(AdminTree.Revocation.Outcome.NOT_BELOW, tree.revoke(A, B).outcome(), "a sibling");
        assertEquals(AdminTree.Revocation.Outcome.NOT_BELOW, tree.revoke(A1, A).outcome(), "an ancestor");
        assertEquals(AdminTree.Revocation.Outcome.NOT_BELOW, tree.revoke(B, A1).outcome(),
                "somebody on another branch");
        assertEquals(AdminTree.Revocation.Outcome.NOT_BELOW, tree.revoke(ROOT, OUTSIDER).outcome(),
                "somebody who is no admin at all");
        assertEquals(AdminTree.Revocation.Outcome.ACTOR_NOT_ADMIN, tree.revoke(OUTSIDER, A).outcome());
        assertEquals(Set.of(ROOT, A, B, A1, A1X), admins(), "no refusal changed anything");

        final AdminTree.Revocation revocation = tree.revoke(ROOT, A);
        assertEquals(AdminTree.Revocation.Outcome.REVOKED, revocation.outcome());
        assertEquals(List.of(A, A1, A1X), revocation.removed(), "the target first, then its branch");
        assertEquals(Set.of(ROOT, B), admins());
        assertEquals(0, count("SELECT count(*) FROM discord_user WHERE admin_granted_by IS NOT NULL"
                + " AND NOT admin"), "a non-admin keeps no granter");
    }

    @Test
    @DisplayName("a grandparent may revoke a grandchild, not only a child")
    void revokingReachesTheWholeBranchBelow() {
        buildTree();

        assertEquals(List.of(A1X), tree.revoke(ROOT, A1X).removed());
        assertEquals(Set.of(ROOT, A, B, A1), admins());
    }

    @Test
    @DisplayName("nobody revokes themselves, not even the root")
    void nobodyRevokesThemselves() {
        buildTree();

        assertEquals(AdminTree.Revocation.Outcome.SELF, tree.revoke(ROOT, ROOT).outcome());
        assertEquals(AdminTree.Revocation.Outcome.SELF, tree.revoke(A, A).outcome());
        assertEquals(Set.of(ROOT, A, B, A1, A1X), admins());
    }

    @Test
    @DisplayName("leaving the guild drops the branch whoever is above it")
    void leavingDropsTheBranch() {
        buildTree();

        assertEquals(Set.of(A1, A1X), tree.dropWithBranch(A1));
        assertEquals(Set.of(ROOT, A, B), admins());
        assertEquals(Set.of(), tree.dropWithBranch(OUTSIDER), "a non-admin leaving changes nothing");
    }

    @Test
    @DisplayName("every account that stops being an admin is notified, once the change committed")
    void aRevocationNotifiesEveryAccountInTheBranch() throws Exception {
        buildTree();

        try (Connection listening = dataSource.getConnection()) {
            try (Statement statement = listening.createStatement()) {
                statement.execute("LISTEN nordtal_admin");
            }

            tree.revoke(ROOT, A);

            final List<String> payloads = new ArrayList<>();
            final PGConnection pg = listening.unwrap(PGConnection.class);
            for (int attempt = 0; attempt < 10 && payloads.size() < 3; attempt++) {
                final PGNotification[] arrived = pg.getNotifications(1000);
                if (arrived != null) {
                    for (final PGNotification each : arrived) {
                        payloads.add(each.getParameter());
                    }
                }
            }
            assertEquals(Set.of(A, A1, A1X), Set.copyOf(payloads));
        }
    }

    @Test
    @DisplayName("a grant notifies too")
    void aGrantNotifies() throws Exception {
        tree.claimRootIfNobody(ROOT);

        try (Connection listening = dataSource.getConnection()) {
            try (Statement statement = listening.createStatement()) {
                statement.execute("LISTEN nordtal_admin");
            }

            tree.grant(ROOT, A);

            final PGNotification[] arrived = listening.unwrap(PGConnection.class).getNotifications(5000);
            assertNotNull(arrived, "no notification arrived on nordtal_admin within 5s");
            assertEquals(A, arrived[0].getParameter());
        }
    }

    // ---------------------------------------------------------------- the schema

    @Test
    @DisplayName("the flag cannot be raised without a grant - the old role mirror's write is refused")
    void theFlagNeedsAGrant() {
        assertThrows(SQLException.class, () -> executeChecked(
                "UPDATE discord_user SET admin = true WHERE discord_id = '" + A + "'"));
        assertThrows(SQLException.class, () -> executeChecked(
                "INSERT INTO discord_user (discord_id, admin) VALUES ('500000000000000066', true)"));
    }

    @Test
    @DisplayName("there is one root at most, even written by hand")
    void oneRootAtMost() {
        tree.claimRootIfNobody(ROOT);

        assertThrows(SQLException.class, () -> executeChecked("UPDATE discord_user SET admin = true,"
                + " admin_granted_at = now() WHERE discord_id = '" + A + "'"));
    }

    // ---------------------------------------------------------------- helpers

    /** ROOT above A and B; A above A1; A1 above A1X. Four grants, so the limit is lifted between. */
    private void buildTree() {
        tree.claimRootIfNobody(ROOT);
        assertEquals(AdminTree.Grant.GRANTED, tree.grant(ROOT, A));
        assertEquals(AdminTree.Grant.GRANTED, tree.grant(ROOT, B));
        assertEquals(AdminTree.Grant.GRANTED, tree.grant(A, A1));
        execute("UPDATE admin_grant SET granted = granted - interval '2 hours'");
        assertEquals(AdminTree.Grant.GRANTED, tree.grant(A1, A1X));
    }

    private Set<String> admins() {
        return Set.copyOf(tree.admins().stream().map(AdminTree.Admin::discordId).toList());
    }

    private static void execute(final String sql) {
        try {
            executeChecked(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test setup statement failed: " + sql, exception);
        }
    }

    private static void executeChecked(final String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(final String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), "expected a row from: " + sql);
            return rows.getLong(1);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test query failed: " + sql, exception);
        }
    }
}
