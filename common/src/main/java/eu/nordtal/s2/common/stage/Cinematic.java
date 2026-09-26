package eu.nordtal.s2.common.stage;

import eu.nordtal.s2.common.feedback.Feedback;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * One staged moment as a value: images shown in the title slot for some ticks each, plus effect and sound.
 *
 * {@link Cinematics} runs one on a {@link CinematicStage}. An image is a {@link Component}, which carries
 * its font; an effect is a namespaced key and a sound a {@link Feedback} category, resolved by the adapter.
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
                throw new IllegalArgumentException("a potion amplifier starts at 0, not " + amplifier);
            }
        }
    }

    /** Which image is on screen at which tick. What {@link Cinematics} schedules, one each. */
    public record Cue(int atTick, Frame frame) {}

    private final List<Frame> frames;
    private final @Nullable Component subtitle;
    private final @Nullable Effect effect;
    private final @Nullable Feedback sound;

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
    public @Nullable Component subtitle() {
        return subtitle;
    }

    /** The effect held for {@link #totalTicks()}, or {@code null} when there is none. */
    public @Nullable Effect effect() {
        return effect;
    }

    /** The category played once at the start, or {@code null} when the moment is silent. */
    public @Nullable Feedback sound() {
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

    /** Returns every image with the tick it appears on, the first at zero. */
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
        private @Nullable Component subtitle;
        private @Nullable Effect effect;
        private @Nullable Feedback sound;

        private Builder() {}

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
        public Builder subtitle(final @Nullable Component subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        /** Held for the whole staging. {@code null} leaves it out. */
        public Builder effect(final @Nullable Effect effect) {
            this.effect = effect;
            return this;
        }

        /** Played once, at the start. {@code null} leaves it out, and so does a blank key. */
        public Builder sound(final @Nullable Feedback sound) {
            this.sound = sound;
            return this;
        }

        public Cinematic build() {
            return new Cinematic(this);
        }
    }
}
