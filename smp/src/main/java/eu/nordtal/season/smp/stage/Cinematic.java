package eu.nordtal.season.smp.stage;

import eu.nordtal.season.messages.feedback.Feedback;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/** A sequence of title images with an optional subtitle, effect and sound. */
public final class Cinematic {

    /**
     * One title image.
     *
     * @param image the image, naming its font if it needs one
     * @param ticks how long it stays on screen, at least 1
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
     * A potion effect that lasts as long as the staging.
     *
     * @param type      the namespaced key of the effect, such as {@code minecraft:blindness}
     * @param amplifier the level, starting at 0
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

    public @Nullable Component subtitle() {
        return subtitle;
    }

    public @Nullable Effect effect() {
        return effect;
    }

    public @Nullable Feedback sound() {
        return sound;
    }

    public int totalTicks() {
        int total = 0;
        for (final Frame frame : frames) {
            total += frame.ticks();
        }
        return total;
    }

    /** Returns every image with the tick it appears on. The first cue is at tick 0. */
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

        /** Adds an image that stays {@code ticks} after the images already added. */
        public Builder frame(final Component image, final int ticks) {
            frames.add(new Frame(image, ticks));
            return this;
        }

        public Builder frames(final List<Component> images, final int ticksEach) {
            for (final Component image : images) {
                frame(image, ticksEach);
            }
            return this;
        }

        public Builder subtitle(final @Nullable Component subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        public Builder effect(final @Nullable Effect effect) {
            this.effect = effect;
            return this;
        }

        /** Sets the sound. A {@code null} or blank key sets none. */
        public Builder sound(final @Nullable Feedback sound) {
            this.sound = sound;
            return this;
        }

        public Cinematic build() {
            return new Cinematic(this);
        }
    }
}
