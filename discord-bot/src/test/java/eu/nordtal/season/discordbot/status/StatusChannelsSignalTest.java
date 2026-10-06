package eu.nordtal.season.discordbot.status;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.phase.PhaseDirectory;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.messages.Messages;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** What makes the status channel names follow a change: the channels they wait on, and one tick for a burst. */
class StatusChannelsSignalTest {

    private final AtomicInteger ticks = new AtomicInteger();
    private final ManualScheduler scheduler = new ManualScheduler();

    private StatusChannels channels() {
        final PhaseDirectory phases = (PhaseDirectory) Proxy.newProxyInstance(
                PhaseDirectory.class.getClassLoader(), new Class<?>[] {PhaseDirectory.class}, (proxy, method, args) -> {
                    ticks.incrementAndGet();
                    return SeasonPhase.SMP;
                });
        final JDA jda = (JDA) Proxy.newProxyInstance(
                JDA.class.getClassLoader(), new Class<?>[] {JDA.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        final GuildLanguages noStatusChannel = GuildLanguages.of(
                List.of(new GuildLanguages.Language("en", "English", "", "", "", "")), new Languages(List.of("en")));
        return new StatusChannels(
                jda,
                noStatusChannel,
                DiscordRenderer.of(Messages.load("messages/access", Locale.ENGLISH)),
                phases,
                () -> NetworkSnapshot.EMPTY,
                Clock.systemUTC());
    }

    @Test
    void aBurstOfSignalsIsOneTickAfterTheSettleTime() {
        final Runnable signal = channels().tickOnSignal(scheduler, Runnable::run);

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
    void followingWaitsOnThePhaseAndOnTheRoundsTeamsAndGames() {
        final SignalHub hub = new SignalHub(
                connected -> {
                    throw new SQLException("never started");
                },
                "test",
                LoggerFactory.getLogger(StatusChannelsSignalTest.class));

        channels().follow(hub, scheduler, Runnable::run);

        assertEquals(EnumSet.of(Channel.PHASE, Channel.HUNGER_GAMES), hub.channels());
    }
}
