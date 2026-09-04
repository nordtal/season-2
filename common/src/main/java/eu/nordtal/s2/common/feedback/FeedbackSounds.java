package eu.nordtal.s2.common.feedback;

import net.kyori.adventure.key.Key;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A module's whole sound configuration, parsed once and then answered from memory.
 *
 * <p>This is the {@code :common} half of the sound vocabulary: it holds what each {@link Feedback}
 * category maps to, as plain values, and it holds the two rules that make a wrong value harmless.
 * The other half is a platform adapter - one per Paper module - which takes the answer from here and
 * turns it into a packet.
 *
 * <h2>An empty key silences the category, and that is the escape hatch</h2>
 * A sound that turns out to be irritating with twenty people in a tavern is not worth a release to
 * fix. Blanking the key in {@code config.yml} switches that category off everywhere in the module at
 * once, which is also why call sites choose a category rather than a sound: silencing "the server
 * said no" has to mean silencing all of it, not thirteen edits.
 *
 * <h2>A bad value is never an exception on a player path</h2>
 * Two things can be wrong and neither of them stops anything:
 *
 * <ul>
 *   <li><b>At load</b>: a key that is not a parseable namespaced key, or a volume or pitch that
 *       makes no sense. Reported once, through the {@code problems} sink the caller passes in, and
 *       the category is silenced or the number replaced. It deliberately does <em>not</em> stop the
 *       plugin the way a bad world name does: a typo in a chime is not worth a season offline, and
 *       the console line says exactly what was ignored.</li>
 *   <li><b>At play</b>: the adapter calls {@link #failed} when the platform threw. Reported once
 *       and the category silenced from then on, because a sound that throws once throws every
 *       time and the alternative is that line in the log per click.</li>
 * </ul>
 *
 * <p>A key that parses but names no sound the server knows is <b>not</b> an error here and must not
 * become one: that is precisely the shape of a custom sound shipped in the resource pack. The client
 * simply plays nothing, which Paper's own {@code playSound(Location, String, ...)} documents.
 */
public final class FeedbackSounds {

    private final Map<Feedback, FeedbackSound> byCategory;

    /**
     * Categories whose sound threw when it was played, so the complaint is made once.
     *
     * <p>Concurrent because {@link #failed} is called from wherever a sound is played. In this
     * repository that is always the main thread, but a class in {@code :common} has no way to insist
     * on that and the cost of being right anyway is one set.
     */
    private final Set<Feedback> broken = ConcurrentHashMap.newKeySet();

    private FeedbackSounds(final Map<Feedback, FeedbackSound> byCategory) {
        this.byCategory = byCategory;
    }

    /**
     * Parses a module's declarations.
     *
     * @param declared what {@code config.yml} said, per category. A category that is absent, or
     *                 whose key is blank, is silent - deliberately and without a complaint
     * @param problems where a value that had to be ignored or corrected is reported, once each.
     *                 A plugin passes {@code getLogger()::warning}
     */
    public static FeedbackSounds parse(final Map<Feedback, FeedbackSound> declared,
                                       final Consumer<String> problems) {
        final Map<Feedback, FeedbackSound> parsed = new EnumMap<>(Feedback.class);
        for (final Map.Entry<Feedback, FeedbackSound> entry : declared.entrySet()) {
            final Feedback category = entry.getKey();
            final FeedbackSound sound = entry.getValue();
            if (sound == null || sound.key() == null || sound.key().isBlank()) {
                continue;
            }

            final String key = sound.key().trim();
            if (!Key.parseable(key)) {
                problems.accept("the sound for " + category + " is '" + key + "', which is not a"
                        + " namespaced key (it has to look like minecraft:ui.button.click - lower"
                        + " case, and the path may only carry letters, digits, _ - . and /). That"
                        + " category is silent until it is corrected.");
                continue;
            }
            parsed.put(category, new FeedbackSound(key,
                    volume(category, sound.volume(), problems),
                    pitch(category, sound.pitch(), problems)));
        }
        return new FeedbackSounds(parsed);
    }

    /** Everything silent - what a module gets before its config is read, and what tests use. */
    public static FeedbackSounds silent() {
        return new FeedbackSounds(new EnumMap<>(Feedback.class));
    }

    /**
     * What to play for {@code category}, or {@code null} when it is silent.
     *
     * <p>Null rather than an {@link java.util.Optional} on purpose: this is called on every click of
     * every menu of every player, and the adapter's whole body is a null check.
     */
    public FeedbackSound sound(final Feedback category) {
        return broken.contains(category) ? null : byCategory.get(category);
    }

    /** Whether {@code category} will play nothing - because it is unset, wrong, or has failed. */
    public boolean isSilent(final Feedback category) {
        return sound(category) == null;
    }

    /**
     * Called by a platform adapter when playing {@code category} actually threw.
     *
     * <p>Silences it and complains once. The alternative - letting it throw - puts a stack trace on
     * a player's click path, which is the one thing the whole of this class exists to prevent.
     *
     * @return true the first time, so the adapter can log the cause with it
     */
    public boolean failed(final Feedback category, final Throwable cause, final Consumer<String> problems) {
        if (!broken.add(category)) {
            return false;
        }
        final FeedbackSound sound = byCategory.get(category);
        problems.accept("the sound for " + category + " ("
                + (sound == null ? "none" : sound.key()) + ") could not be played and is switched"
                + " off for the rest of this run: " + cause);
        return true;
    }

    private static float volume(final Feedback category, final float declared,
                                final Consumer<String> problems) {
        if (declared >= 0.0f && Float.isFinite(declared)) {
            return declared;
        }
        problems.accept("the volume for " + category + " is " + declared + ", which is not a volume;"
                + " using " + FeedbackSound.DEFAULT_VOLUME);
        return FeedbackSound.DEFAULT_VOLUME;
    }

    private static float pitch(final Feedback category, final float declared,
                               final Consumer<String> problems) {
        if (!Float.isFinite(declared) || declared <= 0.0f) {
            problems.accept("the pitch for " + category + " is " + declared + ", which is not a"
                    + " pitch; using " + FeedbackSound.DEFAULT_PITCH);
            return FeedbackSound.DEFAULT_PITCH;
        }
        if (declared < FeedbackSound.MIN_PITCH || declared > FeedbackSound.MAX_PITCH) {
            // Clamped rather than refused: the client clamps it anyway, so refusing would only mean
            // silence where the operator expected the nearest thing they can actually hear.
            final float clamped = Math.min(FeedbackSound.MAX_PITCH,
                    Math.max(FeedbackSound.MIN_PITCH, declared));
            problems.accept("the pitch for " + category + " is " + declared + ", outside the "
                    + FeedbackSound.MIN_PITCH + " - " + FeedbackSound.MAX_PITCH + " a client will"
                    + " play; using " + clamped);
            return clamped;
        }
        return declared;
    }
}
