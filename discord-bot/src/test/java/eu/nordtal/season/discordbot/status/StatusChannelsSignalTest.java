package eu.nordtal.season.discordbot.status;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StatusChannelsSignalTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");
    private static final SeasonState CLOSED = new SeasonState(null, false, SeasonMode.HUNGER_GAMES);

    private final AtomicInteger ticks = new AtomicInteger();
    private final AtomicReference<SeasonState> state = new AtomicReference<>(CLOSED);
    private final ManualScheduler scheduler = new ManualScheduler();

    private StatusChannels channels(final String statusChannel) {
        final SeasonDirectory season = (SeasonDirectory) Proxy.newProxyInstance(
                SeasonDirectory.class.getClassLoader(),
                new Class<?>[] {SeasonDirectory.class},
                (proxy, method, args) -> {
                    ticks.incrementAndGet();
                    return state.get();
                });
        // The channel is not found, so a tick renders and renames nothing.
        final JDA jda = (JDA) Proxy.newProxyInstance(
                JDA.class.getClassLoader(), new Class<?>[] {JDA.class}, (proxy, method, args) -> null);
        final GuildLanguages languages = GuildLanguages.of(
                List.of(new GuildLanguages.Language("en", "English", "", "", "", statusChannel)),
                new Languages(List.of("en")));
        return new StatusChannels(
                jda,
                languages,
                StoredDocumentsStandIn.renderer(),
                season,
                () -> NetworkSnapshot.EMPTY,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SignalHub hub() {
        return new SignalHub(
                connected -> {
                    throw new SQLException("never started");
                },
                "test",
                LoggerFactory.getLogger(StatusChannelsSignalTest.class));
    }

    @Test
    void aBurstOfSignalsIsOneTickAfterTheSettleTime() {
        final Runnable signal = channels("100").tickOnSignal(scheduler, Runnable::run);

        for (int write = 0; write < 5; write++) {
            signal.run();
        }
        assertEquals(1, scheduler.pending().size(), "five signals share the one tick that is waiting");
        assertEquals(Duration.ofSeconds(2), scheduler.pending().getFirst().delay());
        assertEquals(0, ticks.get(), "the tick waits, so the signals behind the first can join it");

        scheduler.runPending();
        assertEquals(1, ticks.get());
    }

    @Test
    void followingWaitsOnTheSeasonAndOnTheRoundsTeamsAndGames() {
        final SignalHub hub = hub();

        channels("100").follow(hub, scheduler, Runnable::run);

        assertEquals(EnumSet.of(Channel.SEASON, Channel.HUNGER_GAMES), hub.channels());
    }

    @Test
    void aLaunchDateAheadIsReadAgainJustAfterItPassesThoughNoSignalSaysSo() {
        final StatusChannels channels = channels("100");
        channels.follow(hub(), scheduler, Runnable::run);
        state.set(CLOSED.withLaunch(NOW.plus(Duration.ofHours(3))));

        channels.tick();
        channels.tick();

        assertEquals(1, scheduler.pending().size(), "one tick waits for the launch, however often the name is read");
        assertEquals(
                Duration.ofHours(3).plus(StatusChannels.PAST_LAUNCH),
                scheduler.pending().getFirst().delay());
        scheduler.runPending();
        assertEquals(3, ticks.get());
    }

    @Test
    void aMovedLaunchDateMovesItsTickAndAClosedSeasonHasNone() {
        final StatusChannels channels = channels("100");
        channels.follow(hub(), scheduler, Runnable::run);
        state.set(CLOSED.withLaunch(NOW.plus(Duration.ofHours(3))));
        channels.tick();

        state.set(CLOSED.withLaunch(NOW.plus(Duration.ofDays(2))));
        channels.tick();
        assertEquals(List.of(Duration.ofDays(2).plus(StatusChannels.PAST_LAUNCH)), delays());

        state.set(CLOSED);
        channels.tick();
        assertEquals(List.of(), delays());
    }

    @Test
    void withoutAStatusChannelNothingIsReadOrScheduled() {
        final StatusChannels channels = channels("");
        channels.follow(hub(), scheduler, Runnable::run);
        state.set(CLOSED.withLaunch(NOW.plus(Duration.ofHours(3))));

        channels.tick();

        assertEquals(0, ticks.get());
        assertEquals(List.of(), delays());
    }

    private List<Duration> delays() {
        return scheduler.pending().stream().map(ManualScheduler.Pending::delay).toList();
    }
}
