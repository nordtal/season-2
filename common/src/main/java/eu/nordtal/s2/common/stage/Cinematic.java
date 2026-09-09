package eu.nordtal.s2.common.stage;

import eu.nordtal.s2.common.feedback.Feedback;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One staged moment, described rather than performed.
 *
 * <h2>What a staging is</h2>
 * A run of <b>images</b> shown one after another in the title slot, each for a number of ticks, plus
 * three optional things that belong to the same moment: a <b>potion effect</b> for as long as it
 * lasts, a <b>sound</b> at the start, and a <b>subtitle</b> under every image. That is the whole
 * vocabulary, and it is deliberately small: the first user is the welcome a player sees on the very
 * first join of the season, and the ones the owner has named for later - a milestone finished, the
 * start event opening, a hunger games winner, a prestige level - are the same five things with
 * different values in them.
 *
 * <p>Nothing here performs anything. This is a value: it can be built on any thread, compared,
 * logged, and asserted against without a server. {@link Cinematics} is what runs one and
 * {@link CinematicStage} is what a surface has to be able to do; the split is what makes the
 * ordering and the spacing testable in a JVM with no client in it.
 *
 * <h2>An image is a Component, never a code point</h2>
 * The obvious shape for "a sequence of pictures" is a list of code points, and it is wrong here:
 * this repository has four fonts that allocate independently, so a code point in the wrong font
 * draws <em>another glyph</em> rather than nothing (see {@code Glyphs} and CLAUDE.md). A
 * {@link Component} carries its font with it, so the caller that knows which font the frames were
 * drawn in is the one that says so - and a frame that is plain text, which is what a placeholder is,
 * costs nothing extra.
 *
 * <h2>An effect is a namespaced key, never a Bukkit type</h2>
 * The same reasoning {@code FeedbackSound} gives for sounds, for the same reason: {@code :common} is
 * compiled against neither Paper nor Velocity, and the registry key is the stable identifier while
 * the generated constants are documented as removable between versions. Resolving it is the platform
 * adapter's job, and a key that names nothing is one warning rather than a moment that throws.
 *
 * <h2>A sound is a category, never a key</h2>
 * {@link Feedback} is the network's whole sound vocabulary and a call site picks one of ten. That is
 * what makes "a blank key in {@code sounds.yml} plays nothing" true here for free, rather than being
 * a rule this class would have to re-implement.
 */
public final class Cinematic {

    /**
     * One image and how long it stays.
     *
     * @param image the picture, as a component that already names its font if it needs one
     * @param ticks how long it is on screen before the next one replaces it, at least 1
     */
    public record Frame(Component image, int ticks) {

        public Frame {
            Objects.requireNonNull(image, "image");
            if (ticks < 1) {
                throw new IllegalArgumentException("a frame is on screen for at least one tick,"
                        + " not " + ticks + " - a frame of zero ticks is a frame nobody sees, and a"
                        + " sequence of them is a staging that is over before it starts");
            }
        }
    }

    /**
     * A potion effect held for the length of the staging.
     *
     * @param type      the effect's namespaced key, e.g. {@code minecraft:blindness}
     * @param amplifier the level, 0 being the first
     */
    public record Effect(String type, int amplifier) {

        public Effect {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("an Effect needs a namespaced key; leave the"
                        + " effect out of the staging instead of naming a blank one");
            }
            if (amplifier < 0) {
                throw new IllegalArgumentException("a potion amplifier starts at 0, not "
                        + amplifier);
            }
        }
    }

    /** Which image is on screen at which tick. What {@link Cinematics} schedules, one each. */
    public record Cue(int atTick, Frame frame) {
    }

    private final List<Frame> frames;
    private final Component subtitle;
    private final Effect effect;
    private final Feedback sound;

    private Cinematic(final Builder builder) {
        this.frames = List.copyOf(builder.frames);
        this.subtitle = builder.subtitle;
        this.effect = builder.effect;
        this.sound = builder.sound;
        if (this.frames.isEmpty()) {
            throw new IllegalArgumentException("a staging with no frames shows nothing at all, for"
                    + " however long its effect lasts - which is a player standing blind in silence"
                    + " with no way to tell that anything is happening");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Frame> frames() {
        return frames;
    }

    /** The line under every image, or {@code null} when there is none. */
    public Component subtitle() {
        return subtitle;
    }

    /** The effect held for {@link #totalTicks()}, or {@code null} when there is none. */
    public Effect effect() {
        return effect;
    }

    /** The category played once at the start, or {@code null} when the moment is silent. */
    public Feedback sound() {
        return sound;
    }

    /** How long the whole thing lasts, in ticks. */
    public int totalTicks() {
        int total = 0;
        for (final Frame frame : frames) {
            total += frame.ticks();
        }
        return total;
    }

    /**
     * Every image with the tick it appears on, first at zero.
     *
     * <p>Public because it is the arithmetic worth asserting: the order the frames run in and the
     * gap between them is the whole of what "the sequence played correctly" means, and it is
     * answerable here without a scheduler, a player or a client.
     */
    public List<Cue> cues() {
        final List<Cue> cues = new ArrayList<>(frames.size());
        int at = 0;
        for (final Frame frame : frames) {
            cues.add(new Cue(at, frame));
            at += frame.ticks();
        }
        return List.copyOf(cues);
    }

    public static final class Builder {

        private final List<Frame> frames = new ArrayList<>();
        private Component subtitle;
        private Effect effect;
        private Feedback sound;

        private Builder() {
        }

        /** Adds one image, shown for {@code ticks} after everything already added. */
        public Builder frame(final Component image, final int ticks) {
            frames.add(new Frame(image, ticks));
            return this;
        }

        /** Adds several images that each stay the same length - the ordinary animated case. */
        public Builder frames(final List<Component> images, final int ticksEach) {
            for (final Component image : images) {
                frame(image, ticksEach);
            }
            return this;
        }

        /** The line under every image. {@code null} leaves it out. */
        public Builder subtitle(final Component subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        /** Held for the whole staging. {@code null} leaves it out. */
        public Builder effect(final Effect effect) {
            this.effect = effect;
            return this;
        }

        /** Played once, at the start. {@code null} leaves it out, and so does a blank key. */
        public Builder sound(final Feedback sound) {
            this.sound = sound;
            return this;
        }

        public Cinematic build() {
            return new Cinematic(this);
        }
    }
}
