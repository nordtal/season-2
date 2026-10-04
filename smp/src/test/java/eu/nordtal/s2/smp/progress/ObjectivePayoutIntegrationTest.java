package eu.nordtal.s2.smp.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.smp.announce.Announcer;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveType;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.port.PrizeSource;
import eu.nordtal.s2.smp.wheel.ExtraSpins;
import eu.nordtal.s2.smp.wheel.SpinDao;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * An objective finished by play pays its whole pot, however many credits it took to finish, or nothing at all.
 *
 * Finishing one hands its announcement to the main thread, so the plugin and the server are fakes that drop it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObjectivePayoutIntegrationTest {

    private static final DiscordId PLAYER = DiscordId.of("100000000000000001");
    private static final UUID MINECRAFT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final NamespacedKey IRON_TOOLS = NamespacedKey.minecraft("story/iron_tools");
    private static final int POT = 30;
    private static final Logger LOGGER = Logger.getLogger(ObjectivePayoutIntegrationTest.class.getName());

    private DataSource dataSource;
    private Jdbi jdbi;
    private ObjectiveEngine engine;
    /** Whether the wheel refuses to grant, as a database that fails half way through a payout would. */
    private boolean grantsFail;

    private @Nullable Server replaced;

    @BeforeAll
    void startDatabaseAndServer() throws ReflectiveOperationException {
        dataSource = TestDatabase.fresh().dataSource();
        jdbi = Jdbis.over(dataSource);

        // Bukkit.setServer also logs the running build, which only a real server can name.
        final Field server = bukkitServer();
        replaced = (Server) server.get(null);
        final BukkitScheduler scheduler =
                fake(BukkitScheduler.class, Map.of("runTask", fake(BukkitTask.class, Map.of())));
        server.set(null, fake(Server.class, Map.of("getLogger", LOGGER, "getScheduler", scheduler)));
    }

    @AfterAll
    void restoreServer() throws ReflectiveOperationException {
        bukkitServer().set(null, replaced);
    }

    @BeforeEach
    void activeFoothold() {
        grantsFail = false;
        execute("TRUNCATE TABLE smp_milestone, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + PLAYER.value() + "')");
        execute("INSERT INTO smp_milestone (key, state) VALUES ('foothold', 'ACTIVE')");
        // Two objectives, so finishing one leaves the milestone and its unlock ceremony alone.
        execute("INSERT INTO smp_objective (milestone_key, key, type, target) VALUES"
                + " ('foothold', 'logs', 'HAND_IN', 64),"
                + " ('foothold', 'iron-tools', 'ADVANCEMENT', 1)");

        final MilestoneTrack track = new MilestoneTrack(List.of(new Milestone(
                "foothold",
                Unlock.BORDER,
                99,
                POT,
                false,
                List.of(
                        new Objective(
                                "logs", ObjectiveType.HAND_IN, "gathering", 64, List.of("OAK_LOG"), "", List.of(), ""),
                        new Objective(
                                "iron-tools",
                                ObjectiveType.ADVANCEMENT,
                                "participation",
                                1,
                                List.of(),
                                "",
                                List.of(),
                                "minecraft:story/iron_tools")))));

        final Messages messages =
                Messages.load(ObjectivePayoutIntegrationTest.class.getClassLoader(), "messages/smp", Locale.ENGLISH);
        final Announcer announcer = new Announcer(
                Inbox.over(dataSource, BotRequest.TABLE), messages.locales(), Runnable::run, (message, failure) -> {});
        engine = new ObjectiveEngine(
                fake(Plugin.class, Map.of("getLogger", LOGGER)),
                jdbi,
                () -> track,
                null,
                null,
                null,
                MessageRenderer.of(messages),
                wheel(),
                null,
                null,
                announcer);
    }

    @Test
    void oneDeliveryThatFinishesAnObjectivePaysItsWholePot() {
        engine.credit(PLAYER, "logs", 64L, MINECRAFT_ID);

        assertEquals(POT, auraPaidFor("logs"), "the only contributor takes the whole pot");
    }

    @Test
    void anObjectiveFinishedOverSeveralDeliveriesPaysItsWholePot() {
        engine.credit(PLAYER, "logs", 32L, MINECRAFT_ID);
        assertEquals(0, auraPaidFor("logs"), "half way, nothing is paid yet");

        engine.credit(PLAYER, "logs", 32L, MINECRAFT_ID);
        assertEquals(POT, auraPaidFor("logs"));
    }

    @Test
    void aGateFinishedByItsLastHolderPaysItsWholePot() {
        engine.creditAdvancement(PLAYER, IRON_TOOLS, MINECRAFT_ID);

        assertEquals(POT, auraPaidFor("iron-tools"));
    }

    @Test
    void aPayoutThatFailsHalfWayLeavesNothingBehind() {
        grantsFail = true;

        assertThrows(IllegalStateException.class, () -> engine.credit(PLAYER, "logs", 64L, MINECRAFT_ID));

        assertEquals(0, auraPaidFor("logs"), "the aura booked before the spins failed is taken back");
        assertEquals(0L, number("SELECT amount FROM smp_objective WHERE key = 'logs'"), "so is the delivery");
        assertEquals(0L, number("SELECT count(*) FROM smp_contribution"), "and who made it");
        assertFalse(
                number("SELECT count(*) FROM smp_objective WHERE completed IS NOT NULL") > 0,
                "the objective is still open, so the same delivery can be made again");
    }

    /** The real wheel, which the payout's spins land in, unless a test makes it fail. */
    private PrizeSource wheel() {
        final PrizeSource real = new ExtraSpins(jdbi.onDemand(SpinDao.class), new SmpSpec() {}::wheelExtraSpinPercents);
        return new PrizeSource() {
            @Override
            public int extraSpinsFor(final double sharePercent) {
                return real.extraSpinsFor(sharePercent);
            }

            @Override
            public void grant(final DiscordId discordId, final int spins) {
                if (grantsFail) {
                    throw new IllegalStateException("the wheel's row could not be written");
                }
                real.grant(discordId, spins);
            }
        };
    }

    private long number(final String sql) {
        return jdbi.withHandle(
                handle -> handle.createQuery(sql).mapTo(Long.class).one());
    }

    /** Returns a fake that answers the named methods and refuses every other call. */
    private static <T> T fake(final Class<T> type, final Map<String, Object> answers) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
                    final Object answer = answers.get(method.getName());
                    if (answer == null) {
                        throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
                    }
                    return answer;
                }));
    }

    private static Field bukkitServer() throws NoSuchFieldException {
        final Field server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        return server;
    }

    private int auraPaidFor(final String objective) {
        return jdbi.withHandle(
                handle -> handle.createQuery("SELECT coalesce(sum(delta), 0) FROM smp_aura_event WHERE ref = :ref")
                        .bind("ref", "foothold/" + objective)
                        .mapTo(Integer.class)
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
