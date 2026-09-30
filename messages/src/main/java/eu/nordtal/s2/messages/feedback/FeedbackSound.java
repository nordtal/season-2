package eu.nordtal.s2.messages.feedback;

/**
 * One category's sound, as three plain values.
 * The sound is a namespaced registry key, never a Bukkit enum constant, so a custom resource-pack sound needs no code.
 *
 * @param key the namespaced sound key, such as {@code minecraft:ui.button.click}
 * @param volume how loud, {@code 1.0} being the sound's own level; above 1 only widens the radius others hear it from
 * @param pitch playback speed, 1.0 being unchanged; the client clamps it to 0.5 to 2.0
 */
public record FeedbackSound(String key, float volume, float pitch) {

    /** The volume and pitch a value that made no sense falls back to. */
    public static final float DEFAULT_VOLUME = 1.0f;

    public static final float DEFAULT_PITCH = 1.0f;

    /** The lowest and highest pitch a client will actually play; anything else is clamped there. */
    public static final float MIN_PITCH = 0.5f;

    public static final float MAX_PITCH = 2.0f;

    public FeedbackSound {
        if (key == null) {
            throw new IllegalArgumentException("a FeedbackSound needs a key; use FeedbackSounds to "
                    + "express 'this category is silent' instead");
        }
    }
}
