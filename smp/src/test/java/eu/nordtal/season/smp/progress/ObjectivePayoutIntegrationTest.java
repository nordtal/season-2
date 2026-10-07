package eu.nordtal.season.smp.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.smp.announce.Announcer;
import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.ObjectiveType;
import eu.nordtal.season.smp.milestone.Unlock;
import eu.nordtal.season.smp.port.OwnContributionRow;
import eu.nordtal.season.smp.port.PrizeSource;
import eu.nordtal.season.smp.wheel.ExtraSpins;
import eu.nordtal.season.smp.wheel.SpinDao;
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
 * An objective finished by play pays its whole budgets, however many credits it took to finish, or nothing at all.
 *
 * Finishing one hands its announcement to the main thread, so the plugin and the server are fakes that drop it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObjectivePayoutIntegrationTest {

    private static final DiscordId PLAYER = DiscordId.of("100000000000000001");
    private static final DiscordId OTHER = DiscordId.of("100000000000000002");
    private static final UUID MINECRAFT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final NamespacedKey IRON_TOOLS = NamespacedKey.minecraft("story/iron_tools");
    private static final int POT = 30;
    private static final int SPINS = 20;
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
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + PLAYER.value() + "'), ('" + OTHER.value() + "')");
        execute("INSERT INTO smp_milestone (key, state) VALUES ('foothold', 'ACTIVE')");
        // Two objectives, so finishing one leaves the milestone and its unlock ceremony alone.
        execute("INSERT INTO smp_objective (milestone_key, key, type, target) VALUES"
                + " ('foothold', 'logs', 'HAND_IN', 64),"
                + " ('foothold', 'iron-tools', 'ADVANCEMENT', 1)");

        final MilestoneTrack track = new MilestoneTrack(List.of(new Milestone(
                "foothold",
                Unlock.BORDER,
                99,
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
                                "",
                                POT,
                                SPINS),
                        new Objective(
                                "iron-tools",
                                ObjectiveType.ADVANCEMENT,
                                "participation",
                                1,
                                List.of(),
                                "",
                                List.of(),
                                "minecraft:story/iron_tools",
                                POT,
                                SPINS)))));

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

        assertEquals(POT, auraPaidFor("logs"), "the only contributor takes the whole aura budget");
        assertEquals(SPINS, spinsOf(PLAYER), "and the whole spin budget");
    }

    @Test
    void twoContributorsShareBothBudgetsExactlyAsTheMenuForecastIt() {
        engine.credit(PLAYER, "logs", 48L, MINECRAFT_ID);
        engine.credit(OTHER, "logs", 15L, MINECRAFT_ID);
        assertEquals(
                List.of(new OwnContributionRow("logs", 15L, 64L, 6), new OwnContributionRow("iron-tools", 0L, 1L, 0)),
                engine.ownContributions("foothold", OTHER).stream()
                        .sorted(java.util.Comparator.comparing(OwnContributionRow::key)
                                .reversed())
                        .toList());

        engine.credit(OTHER, "logs", 1L, MINECRAFT_ID);

        // 30 aura: 4 each equally, then 22 by 48 to 16, whose one left over goes to the larger share.
        assertEquals(21, auraOf(PLAYER, "logs"));
        assertEquals(9, auraOf(OTHER, "logs"));
        assertEquals(POT, auraPaidFor("logs"), "the whole aura budget and never more");
        // 20 spins: 3 each equally, then 14 by 48 to 16, the one left over to the larger share again.
        assertEquals(14, spinsOf(PLAYER));
        assertEquals(6, spinsOf(OTHER), "what the menu forecast a delivery earlier");
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
        final PrizeSource real = new ExtraSpins(jdbi.onDemand(SpinDao.class));
        return new PrizeSource() {
            @Override
            public void grant(final DiscordId discordId, final int spins) {
                if (grantsFail) {
                    throw new IllegalStateException("the wheel's row could not be written");
                }
                real.grant(discordId, spins);
            }
        };
    }

    private int spinsOf(final DiscordId player) {
        return (int)
                number("SELECT coalesce(sum(granted), 0) FROM smp_spin WHERE discord_id = '" + player.value() + "'");
    }

    private int auraOf(final DiscordId player, final String objective) {
        return (int) number("SELECT coalesce(sum(delta), 0) FROM smp_aura_event WHERE ref = 'foothold/" + objective
                + "' AND discord_id = '" + player.value() + "'");
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
