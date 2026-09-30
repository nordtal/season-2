package eu.nordtal.s2.smp.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.command.CommandRequests;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.smp.announce.Announcer;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveType;
import eu.nordtal.s2.smp.milestone.Unlock;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.bukkit.NamespacedKey;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * An earned advancement reaches the objective row of the active milestone's gate that names it.
 *
 * Below its target a credit touches only the database and the track, so the ceremony's collaborators are absent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdvancementCreditIntegrationTest {

    private static final DiscordId PLAYER = DiscordId.of("100000000000000001");
    private static final UUID MINECRAFT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final NamespacedKey IRON_TOOLS = NamespacedKey.minecraft("story/iron_tools");
    private static final NamespacedKey MINE_DIAMOND = NamespacedKey.minecraft("story/mine_diamond");

    private DataSource dataSource;
    private Jdbi jdbi;
    private MilestoneTrack track;
    private ObjectiveEngine engine;

    @BeforeAll
    void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
        jdbi = Jdbis.over(dataSource);
    }

    @BeforeEach
    void activeFoothold() {
        execute("TRUNCATE TABLE smp_milestone, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + PLAYER.value() + "')");
        execute("INSERT INTO smp_milestone (key, state) VALUES ('foothold', 'ACTIVE'), ('settlement', 'LOCKED')");
        execute("INSERT INTO smp_objective (milestone_key, key, type, target) VALUES"
                + " ('foothold', 'logs', 'HAND_IN', 64),"
                + " ('foothold', 'iron-tools', 'ADVANCEMENT', 10),"
                + " ('settlement', 'mine-diamond', 'ADVANCEMENT', 10)");
        track = track("minecraft:story/iron_tools");

        final SmpDao dao = jdbi.onDemand(SmpDao.class);
        final Messages messages =
                Messages.load(AdvancementCreditIntegrationTest.class.getClassLoader(), "messages/smp", Locale.ENGLISH);
        final Announcer announcer = new Announcer(
                CommandRequests.borrowing(dataSource),
                messages,
                Runnable::run,
                (message, failure) -> {},
                Clock.systemUTC());
        engine = new ObjectiveEngine(
                null, dao, () -> track, null, null, null, messages, null, null, null, null, announcer);
    }

    @Test
    void anEarnedAdvancementCreditsTheActiveMilestonesGate() {
        assertEquals(1L, engine.creditAdvancement(PLAYER, IRON_TOOLS, MINECRAFT_ID));

        assertEquals(1L, amountOf("foothold", "iron-tools"), "the gate counts the player who earned it");
        assertEquals(1L, contributionOf("iron-tools"), "and the player's share of the pot is 1");
        assertEquals(0L, amountOf("foothold", "logs"));
    }

    @Test
    void theGateOfALockedMilestoneIsNotCredited() {
        assertEquals(0L, engine.creditAdvancement(PLAYER, MINE_DIAMOND, MINECRAFT_ID));

        assertEquals(0L, amountOf("settlement", "mine-diamond"), "settlement is not active yet");
        assertEquals(0L, amountOf("foothold", "iron-tools"));
    }

    @Test
    void aGateNamedWithoutItsNamespaceIsTheMinecraftOne() {
        track = track("story/iron_tools");

        assertEquals(1L, engine.creditAdvancement(PLAYER, IRON_TOOLS, MINECRAFT_ID));
        assertEquals(1L, amountOf("foothold", "iron-tools"));
    }

    private static MilestoneTrack track(final String footholdGate) {
        return new MilestoneTrack(List.of(
                new Milestone(
                        "foothold",
                        Unlock.BORDER,
                        99,
                        30,
                        false,
                        List.of(
                                new Objective(
                                        "logs",
                                        ObjectiveType.HAND_IN,
                                        "gathering",
                                        64,
                                        List.of("OAK_LOG"),
                                        "",
                                        List.of(),
                                        ""),
                                gate("iron-tools", footholdGate))),
                new Milestone(
                        "settlement",
                        Unlock.BORDER,
                        400,
                        60,
                        false,
                        List.of(gate("mine-diamond", "minecraft:story/mine_diamond")))));
    }

    private static Objective gate(final String key, final String advancement) {
        return new Objective(
                key, ObjectiveType.ADVANCEMENT, "participation", 10, List.of(), "", List.of(), advancement);
    }

    private long amountOf(final String milestone, final String objective) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT amount FROM smp_objective WHERE milestone_key = :milestone AND key = :key")
                .bind("milestone", milestone)
                .bind("key", objective)
                .mapTo(Long.class)
                .one());
    }

    private long contributionOf(final String objective) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT coalesce(sum(con.amount), 0) FROM smp_contribution"
                        + " con JOIN smp_objective obj ON obj.id = con.objective_id"
                        + " WHERE obj.key = :key AND con.discord_id = :player")
                .bind("key", objective)
                .bind("player", PLAYER.value())
                .mapTo(Long.class)
                .one());
    }

    private void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }
}
