package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.feedback.FeedbackSounds;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.packrendering.ui.Pill;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.sound.SoundsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.spec.Specs;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** Holds the two HUD lines of a rider: the ride while it runs, how to choose and get out while the cart stands. */
class RideFeedbackTest {

    private static final MessageRenderer RENDERER = MessageRenderer.of(
                    Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
            .reading(new AppliedDocuments(RideFeedbackTest.class.getClassLoader(), Set.of("smp")));

    private static final UUID RIDER = UUID.randomUUID();

    private static final Player PLAYER = (Player) Proxy.newProxyInstance(
            RideFeedbackTest.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                if (method.getName().equals("getUniqueId")) {
                    return RIDER;
                }
                if (method.getName().equals("sendActionBar")) {
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void aStandingCartWithAStationToChooseShowsHowToChooseOverHowToGetOut() {
        final RideFeedback feedback = feedback(Optional.of(true));

        assertEquals(
                List.of(Pill.Content.of(Symbol.RAIL, "Rechtsklick auf die Lore: Bahnhof wählen")),
                feedback.render(PLAYER, Locale.GERMAN));
        assertEquals(List.of(Pill.Content.of("Zum Absteigen schleichen")), feedback.below(PLAYER, Locale.GERMAN));
    }

    @Test
    void aStandingCartWithNoStationToChooseShowsOnlyHowToGetOut() {
        final RideFeedback feedback = feedback(Optional.of(false));

        assertEquals(List.of(Pill.Content.of("Sneak to dismount")), feedback.render(PLAYER, Locale.ENGLISH));
        assertEquals(List.of(), feedback.below(PLAYER, Locale.ENGLISH));
    }

    @Test
    void aPlayerOnFootSeesNoHudLine() {
        final RideFeedback feedback = feedback(Optional.empty());

        assertEquals(List.of(), feedback.render(PLAYER, Locale.ENGLISH));
        assertEquals(List.of(), feedback.below(PLAYER, Locale.ENGLISH));
    }

    @Test
    void aRunningRideIsTheOnlyLine() {
        final RideFeedback feedback = feedback(Optional.of(true));
        feedback.planned(PLAYER, UUID.randomUUID(), "Harbour", 240);

        assertEquals(
                List.of(Pill.Content.of(Symbol.RAIL, "Harbour"), Pill.Content.bar(0, "240 m")),
                feedback.render(PLAYER, Locale.ENGLISH));
        assertEquals(List.of(), feedback.below(PLAYER, Locale.ENGLISH));
    }

    @Test
    void aDepartureStrikesTheLoweredChimeThreeTimesThreeHundredMillisecondsApart() {
        final List<String> heard = new ArrayList<>();
        final List<Duration> delays = new ArrayList<>();
        final List<Runnable> later = new ArrayList<>();
        final RideFeedback feedback = new RideFeedback(
                RENDERER,
                new Identities(players -> List.of()),
                SmpSounds.of(Specs.createDefault(SoundsSpec.class), problem -> heard.add("problem " + problem)),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                scheduler(delays, later),
                player -> Optional.empty());
        final Player rider = listening(heard);

        feedback.planned(rider, UUID.randomUUID(), "Harbour", 240);

        assertEquals(List.of("minecraft:block.note_block.chime 1.0 0.5"), heard, "the first strike sounds at once");
        assertEquals(List.of(Duration.ofMillis(300), Duration.ofMillis(600)), delays);
        later.forEach(Runnable::run);
        assertEquals(
                List.of(
                        "minecraft:block.note_block.chime 1.0 0.5",
                        "minecraft:block.note_block.chime 1.0 0.5",
                        "minecraft:block.note_block.chime 1.0 0.5"),
                heard);
    }

    @Test
    void aRideThatPlansAgainForTheSameStationStrikesNothing() {
        final List<String> heard = new ArrayList<>();
        final List<Duration> delays = new ArrayList<>();
        final List<Runnable> later = new ArrayList<>();
        final RideFeedback feedback = new RideFeedback(
                RENDERER,
                new Identities(players -> List.of()),
                SmpSounds.of(Specs.createDefault(SoundsSpec.class), problem -> heard.add("problem " + problem)),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                scheduler(delays, later),
                player -> Optional.empty());
        final Player rider = listening(heard);
        final UUID station = UUID.randomUUID();
        feedback.planned(rider, station, "Harbour", 240);
        heard.clear();
        delays.clear();

        feedback.planned(rider, station, "Harbour", 200);

        assertEquals(List.of(), heard);
        assertEquals(List.of(), delays);
    }

    /** A rider who hears every sound played to them, as key, volume and pitch. */
    private static Player listening(final List<String> heard) {
        return (Player) Proxy.newProxyInstance(
                RideFeedbackTest.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> RIDER;
                    case "isOnline" -> true;
                    case "getLocation" -> new Location(null, 0, 0, 0);
                    case "sendActionBar" -> null;
                    case "playSound" -> {
                        if (!(args[0] instanceof Location) || args[2] != SoundCategory.MASTER) {
                            throw new UnsupportedOperationException("playSound " + List.of(args));
                        }
                        heard.add(args[1] + " " + args[3] + " " + args[4]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A main thread that keeps each delayed task and its delay, to run them when the test says. */
    private static Scheduler scheduler(final List<Duration> delays, final List<Runnable> later) {
        return new Scheduler() {
            @Override
            public void execute(final Runnable work) {
                later.add(work);
            }

            @Override
            public Task after(final Duration delay, final Runnable work) {
                delays.add(delay);
                later.add(work);
                return () -> {};
            }
        };
    }

    private static RideFeedback feedback(final Optional<Boolean> waiting) {
        return new RideFeedback(
                RENDERER,
                new Identities(players -> List.of()),
                new SmpSounds(FeedbackSounds.silent(), _ -> {}),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                scheduler(new ArrayList<>(), new ArrayList<>()),
                player -> waiting);
    }
}
