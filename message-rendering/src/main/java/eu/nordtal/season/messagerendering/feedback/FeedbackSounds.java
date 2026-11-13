package eu.nordtal.season.messagerendering.feedback;

import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.feedback.FeedbackSound;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import org.jspecify.annotations.Nullable;

/** Holds the sound of each {@code Feedback} category.
 * A blank key silences a category. A bad value is reported once and silenced or corrected, never thrown.
 */
public final class FeedbackSounds {

    private final Map<Feedback, FeedbackSound> byCategory;

    private final Set<Feedback> broken = ConcurrentHashMap.newKeySet();

    private FeedbackSounds(final Map<Feedback, FeedbackSound> byCategory) {
        this.byCategory = byCategory;
    }

    /** Parses a module's declarations. An absent or blank key is silent without a report. */
    public static FeedbackSounds parse(final Map<Feedback, FeedbackSound> declared, final Consumer<String> problems) {
        final Map<Feedback, FeedbackSound> parsed = new EnumMap<>(Feedback.class);
        for (final Map.Entry<Feedback, FeedbackSound> entry : declared.entrySet()) {
            final Feedback category = entry.getKey();
            final FeedbackSound sound = entry.getValue();
            if (sound == null || sound.key() == null || sound.key().isBlank()) {
                continue;
            }

            final String key = sound.key().trim();
            if (!Key.parseable(key)) {
                problems.accept("sound key invalid, category silenced: category=" + category + " key=\"" + key + "\"");
                continue;
            }
            parsed.put(
                    category,
                    new FeedbackSound(
                            key, volume(category, sound.volume(), problems), pitch(category, sound.pitch(), problems)));
        }
        return new FeedbackSounds(parsed);
    }

    public static FeedbackSounds silent() {
        return new FeedbackSounds(new EnumMap<>(Feedback.class));
    }

    /** Returns the sound of {@code category}, or {@code null} when it is silent. */
    public @Nullable FeedbackSound sound(final Feedback category) {
        return broken.contains(category) ? null : byCategory.get(category);
    }

    public boolean isSilent(final Feedback category) {
        return sound(category) == null;
    }

    /**
     * @return {@code true} on the first failure of {@code category}
     */
    public boolean failed(final Feedback category, final Throwable cause, final Consumer<String> problems) {
        if (!broken.add(category)) {
            return false;
        }
        final FeedbackSound sound = byCategory.get(category);
        problems.accept("sound failed, category silenced until restart: category=" + category + " key="
                + (sound == null ? "none" : sound.key()) + " cause=" + cause);
        return true;
    }

    private static float volume(final Feedback category, final float declared, final Consumer<String> problems) {
        if (declared >= 0.0f && Float.isFinite(declared)) {
            return declared;
        }
        problems.accept("sound volume invalid, default used: category=" + category + " volume=" + declared + " default="
                + FeedbackSound.DEFAULT_VOLUME);
        return FeedbackSound.DEFAULT_VOLUME;
    }

    private static float pitch(final Feedback category, final float declared, final Consumer<String> problems) {
        if (!Float.isFinite(declared) || declared <= 0.0f) {
            problems.accept("sound pitch invalid, default used: category=" + category + " pitch=" + declared
                    + " default=" + FeedbackSound.DEFAULT_PITCH);
            return FeedbackSound.DEFAULT_PITCH;
        }
        if (declared < FeedbackSound.MIN_PITCH || declared > FeedbackSound.MAX_PITCH) {
            final float clamped = Math.min(FeedbackSound.MAX_PITCH, Math.max(FeedbackSound.MIN_PITCH, declared));
            problems.accept("sound pitch out of range, clamped: category=" + category + " pitch=" + declared + " min="
                    + FeedbackSound.MIN_PITCH + " max=" + FeedbackSound.MAX_PITCH + " clamped=" + clamped);
            return clamped;
        }
        return declared;
    }
}
