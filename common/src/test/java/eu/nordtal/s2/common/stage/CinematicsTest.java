package eu.nordtal.s2.common.stage;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.feedback.FeedbackSound;
import eu.nordtal.s2.common.feedback.FeedbackSounds;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Running a staging: the order, the spacing, the cancel, and the sound that is not played.
 *
 * <h2>Why this can be tested at all</h2>
 * A staged moment is titles, a potion effect and a sound on a real client, and none of that exists
 * in a JVM with no server in it. What made it testable is the split: {@link Cinematics} holds every
 * decision and reaches the world through {@link Cinematics.Scheduler} and {@link CinematicStage},
 * both of which a test can be. So the four things that can actually go wrong - a frame on the wrong
 * tick, a frame after a cancel, two stagings at once, and a sound played when the configuration says
 * silence - are answerable here rather than by watching somebody join.
 *
 * <p>What it still cannot say is whether a title of twenty ticks with no fade reads as an animation
 * or as a flicker, and whether three seconds of blindness on a first join is welcoming or alarming.
 * Those need a client and are in the owner's checklist outside this repository.
 */
class CinematicsTest {

    private static final Component A = Component.text("a");
    private static final Component B = Component.text("b");
    private static final Component C = Component.text("c");

    private final UUID player = UUID.randomUUID();
    private final FakeScheduler clock = new FakeScheduler();
    private final RecordingStage stage = new RecordingStage();
    private final Cinematics cinematics = new Cinematics(clock);

    @Test
    @DisplayName("the frames appear in order, each on the tick the description puts it")
    void theSequenceRunsInOrderAndOnTime() {
        assertTrue(cinematics.start(player, Cinematic.builder()
                .frame(A, 5)
                .frame(B, 10)
                .frame(C, 1)
                .build(), stage));

        // The first frame is not scheduled at all - it is shown inside start(), so a moment begins
        // without waiting a tick for a scheduler that may be a tick behind.
        assertEquals(List.of(A), stage.shown);

        clock.advanceTo(4);
        assertEquals(List.of(A), stage.shown, "the second frame is due at 5, not before it");

        clock.advanceTo(5);
        assertEquals(List.of(A, B), stage.shown);

        clock.advanceTo(14);
        assertEquals(List.of(A, B), stage.shown, "the third frame is due at 15");

        clock.advanceTo(15);
        assertEquals(List.of(A, B, C), stage.shown);
        assertEquals(List.of(5, 10, 1), stage.stays,
                "each frame's own length is handed to the surface, so the title can be timed to be"
                        + " replaced rather than to fade out between frames");

        assertEquals(0, stage.cleared, "the staging is not over until its last frame has had its"
                + " own length on screen");
        clock.advanceTo(16);
        assertEquals(1, stage.cleared);
        assertFalse(cinematics.isRunning(player), "a staging that has ended is not running, so the"
                + " next one is allowed");
    }

    @Test
    @DisplayName("a cancel stops the frames that had not run and clears the screen once")
    void aCancelReallyCancels() {
        cinematics.start(player, Cinematic.builder().frames(List.of(A, B, C), 10).build(), stage);
        clock.advanceTo(10);
        assertEquals(List.of(A, B), stage.shown);

        cinematics.cancel(player);

        assertEquals(1, stage.cleared, "a cancel has to take the effect off, or a player who left"
                + " mid-staging comes back blind");
        assertFalse(cinematics.isRunning(player));

        clock.advanceTo(100);
        assertEquals(List.of(A, B), stage.shown,
                "a frame scheduled before the cancel still ran afterwards, which puts the staging"
                        + " back on the screen of somebody who has just respawned");
        assertEquals(1, stage.cleared, "clear happens once - the natural end must not arrive after"
                + " a cancel and take the screen a second time");
    }

    @Test
    @DisplayName("cancelling something that is not running does nothing at all")
    void cancellingNothingIsSafe() {
        // Called from a quit handler, which fires for every player on every disconnect - the
        // overwhelming majority of whom have no staging running.
        cinematics.cancel(player);
        assertEquals(0, stage.cleared);
    }

    @Test
    @DisplayName("a second staging is refused while one is running, and allowed once it is over")
    void oneStagingPerPlayer() {
        final RecordingStage second = new RecordingStage();
        cinematics.start(player, Cinematic.builder().frame(A, 10).build(), stage);

        assertFalse(cinematics.start(player, Cinematic.builder().frame(B, 10).build(), second),
                "two stagings at once are two title sequences over one screen and two effects with"
                        + " two end times, the later of which would clear the earlier one's"
                        + " blindness while it is still meant to be running");
        assertEquals(List.of(), second.shown, "the refused staging must not have started anyway");
        assertEquals(0, second.cleared, "a refused staging never ran, so nothing of it is cleared");

        clock.advanceTo(10);
        assertTrue(cinematics.start(player, Cinematic.builder().frame(B, 10).build(), second));
    }

    @Test
    @DisplayName("two players are two stagings")
    void twoPlayersDoNotCollide() {
        final UUID other = UUID.randomUUID();
        final RecordingStage otherStage = new RecordingStage();

        assertTrue(cinematics.start(player, Cinematic.builder().frame(A, 10).build(), stage));
        assertTrue(cinematics.start(other, Cinematic.builder().frame(B, 10).build(), otherStage));

        cinematics.cancel(player);
        assertTrue(cinematics.isRunning(other), "cancelling one player's staging ended another's");
    }

    @Test
    @DisplayName("the disable sweep clears everybody")
    void cancelAllClearsEveryone() {
        final UUID other = UUID.randomUUID();
        final RecordingStage otherStage = new RecordingStage();
        cinematics.start(player, Cinematic.builder().frame(A, 10).build(), stage);
        cinematics.start(other, Cinematic.builder().frame(B, 10).build(), otherStage);

        cinematics.cancelAll();

        assertEquals(1, stage.cleared);
        assertEquals(1, otherStage.cleared);
        assertFalse(cinematics.isRunning(player));
        assertFalse(cinematics.isRunning(other));
    }

    @Test
    @DisplayName("the sound and the effect happen once, at the start")
    void theSoundAndTheEffectOpenTheMoment() {
        cinematics.start(player, Cinematic.builder()
                .frames(List.of(A, B), 10)
                .sound(Feedback.NETWORK_EVENT)
                .effect(new Cinematic.Effect("minecraft:blindness", 0))
                .build(), stage);

        assertEquals(List.of(Feedback.NETWORK_EVENT), stage.played);
        assertEquals(new Cinematic.Effect("minecraft:blindness", 0), stage.effect);
        assertEquals(20, stage.effectTicks,
                "the effect lasts exactly as long as the pictures, so it ends when they do rather"
                        + " than one frame short or a second long");

        clock.advanceTo(100);
        assertEquals(List.of(Feedback.NETWORK_EVENT), stage.played,
                "the opening sound is played once, not once per frame");
    }

    @Test
    @DisplayName("a staging with no sound never asks the surface to play one")
    void noSoundMeansNoCall() {
        cinematics.start(player, Cinematic.builder().frames(List.of(A, B), 10).build(), stage);
        clock.advanceTo(100);

        assertEquals(List.of(), stage.played,
                "a staging whose sound has not been drawn yet must not fall back to some other"
                        + " category - it runs silently");
    }

    @Test
    @DisplayName("a blank key in the sound configuration plays nothing")
    void aBlankSoundKeyIsSilent() {
        // This is the composition the requirement is actually about: the staging names a Feedback
        // category, a Paper module's stage hands that to its sound adapter, and the adapter answers
        // from FeedbackSounds - where a blank key means silence. Written here rather than in
        // :paper-common because playing a sound needs a Player, and the decision does not.
        final List<String> problems = new ArrayList<>();
        final SoundStage silent = new SoundStage(configured("", problems));
        final SoundStage audible = new SoundStage(configured("minecraft:ui.button.click", problems));

        cinematics.start(player, staging(), silent);
        assertEquals(List.of(), silent.keys,
                "a category with no key in sounds.yml still reached a client, which is the escape"
                        + " hatch for an irritating sound not working");

        cinematics.start(UUID.randomUUID(), staging(), audible);
        assertEquals(List.of("minecraft:ui.button.click"), audible.keys,
                "the control: the same staging with a key configured does play it, so the case"
                        + " above is silence and not a staging that plays nothing at all");
        assertEquals(List.of(), problems, "a blank key is deliberate and is not a complaint");
    }

    private static Cinematic staging() {
        return Cinematic.builder().frame(A, 10).sound(Feedback.NETWORK_EVENT).build();
    }

    private static FeedbackSounds configured(final String key, final List<String> problems) {
        final Map<Feedback, FeedbackSound> declared = new EnumMap<>(Feedback.class);
        declared.put(Feedback.NETWORK_EVENT, new FeedbackSound(key, 1.0f, 1.0f));
        return FeedbackSounds.parse(declared, problems::add);
    }

    /** What a Paper module's surface does with a sound, without the Paper. */
    private static final class SoundStage extends RecordingStage {

        private final FeedbackSounds sounds;
        private final List<String> keys = new ArrayList<>();

        private SoundStage(final FeedbackSounds sounds) {
            this.sounds = sounds;
        }

        @Override
        public void play(final Feedback sound) {
            final FeedbackSound resolved = sounds.sound(sound);
            if (resolved != null) {
                keys.add(resolved.key());
            }
        }
    }

    /** A surface that writes down what it was told to draw. */
    private static class RecordingStage implements CinematicStage {

        private final List<Component> shown = new ArrayList<>();
        private final List<Integer> stays = new ArrayList<>();
        private final List<Feedback> played = new ArrayList<>();
        private Cinematic.Effect effect;
        private int effectTicks;
        private int cleared;

        @Override
        public void show(final Component image, final Component subtitle, final int ticks) {
            shown.add(image);
            stays.add(ticks);
        }

        @Override
        public void effect(final Cinematic.Effect effect, final int ticks) {
            this.effect = effect;
            this.effectTicks = ticks;
        }

        @Override
        public void play(final Feedback sound) {
            played.add(sound);
        }

        @Override
        public void clear() {
            cleared++;
        }
    }

    /**
     * A scheduler with a hand-turned clock.
     *
     * <p>{@link #advanceTo} runs everything due up to that tick, in tick order. That is what makes
     * "the second frame is due at 5, not before it" a thing a test can say at all - with a real
     * scheduler it would be a sleep, and a sleep that passes on a fast machine and fails on a busy
     * one is worse than no test.
     */
    private static final class FakeScheduler implements Cinematics.Scheduler {

        private record Scheduled(long at, Runnable body, boolean[] cancelled) {
        }

        private final List<Scheduled> pending = new ArrayList<>();
        private long now;

        @Override
        public Cinematics.Handle later(final Runnable task, final long delayTicks) {
            final Scheduled scheduled = new Scheduled(now + delayTicks, task, new boolean[1]);
            pending.add(scheduled);
            return () -> scheduled.cancelled()[0] = true;
        }

        private void advanceTo(final long tick) {
            now = tick;
            // A copy, because a task may schedule another one - nothing here does today, and a
            // ConcurrentModificationException would be a confusing way to find out that something
            // started to.
            for (final Scheduled scheduled : List.copyOf(pending)) {
                if (!scheduled.cancelled()[0] && scheduled.at() <= tick) {
                    pending.remove(scheduled);
                    scheduled.body().run();
                }
            }
        }
    }
}
