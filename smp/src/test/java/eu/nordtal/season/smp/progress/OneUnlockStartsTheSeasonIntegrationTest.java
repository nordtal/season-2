package eu.nordtal.season.smp.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.smp.announce.Announcer;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.command.SmpAdmin;
import eu.nordtal.season.smp.config.Milestones;
import eu.nordtal.season.smp.config.MilestonesSpec;
import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneState;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.milestone.TrackDao;
import eu.nordtal.season.smp.state.SeasonState;
import eu.nordtal.season.smp.wheel.SpinDao;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * On the default track, one admin unlock of the first milestone starts the season.
 * The next milestone, which has objectives, becomes active. The unlock's announcement goes to the main thread, which
 * the fake server drops.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OneUnlockStartsTheSeasonIntegrationTest {

    private static final Logger LOGGER = Logger.getLogger(OneUnlockStartsTheSeasonIntegrationTest.class.getName());

    private @Nullable Server replaced;

    @BeforeAll
    void fakeServer() throws ReflectiveOperationException {
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

    @Test
    void oneUnlockOfTheFirstMilestoneStartsTheSeason() throws SettingsException {
        final DataSource dataSource = TestDatabase.fresh().dataSource();
        final Jdbi jdbi = Jdbis.over(dataSource);
        final TrackDao rows = jdbi.onDemand(TrackDao.class);
        final MilestoneTrack track = Objects.requireNonNull(Milestones.read(
                        new MemorySettingStore().checked("smp", Group.of("milestones", MilestonesSpec.class), Map.of()))
                .track());
        final Milestone first = track.first().orElseThrow();
        for (final Milestone milestone : track.milestones()) {
            rows.ensureMilestone(milestone.key(), MilestoneState.LOCKED.name());
        }
        rows.activateAfter(first.key(), 0);

        final SeasonState season = new SeasonState();
        season.refresh(List.of(), track);
        final Messages messages = Messages.load(
                OneUnlockStartsTheSeasonIntegrationTest.class.getClassLoader(), "messages/smp", Locale.ENGLISH);
        final ObjectiveEngine engine = new ObjectiveEngine(
                fake(Plugin.class, Map.of("getLogger", LOGGER)),
                jdbi,
                () -> track,
                season,
                null,
                null,
                null,
                null,
                null,
                null,
                new Announcer(
                        Inbox.over(dataSource, BotRequest.TABLE),
                        messages.locales(),
                        Runnable::run,
                        (message, failure) -> {}));
        final SmpAdmin admin = new SmpAdmin(
                rows,
                jdbi.onDemand(AuraDao.class),
                jdbi.onDemand(SpinDao.class),
                new SmpAdmin.Track() {
                    @Override
                    public void finishObjective(final String milestone, final ObjectiveRow objective) {}

                    @Override
                    public void unlockMilestone(final String milestone) {
                        engine.unlockMilestone(milestone, null);
                    }
                },
                null,
                LOGGER);

        assertInstanceOf(Answer.Done.class, admin.unlockMilestone(first.key()));

        final Milestone next = track.after(first.key()).orElseThrow();
        assertEquals(Optional.of(next.key()), rows.activeMilestoneKey());
        assertEquals(List.of(first.key()), rows.completedMilestoneKeys());
        assertEquals(first.borderDiameter(), season.borderDiameter(), "the first unlock opens the first border");
        assertFalse(next.objectives().isEmpty(), "the season runs on objectives after one unlock");
    }

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
}
