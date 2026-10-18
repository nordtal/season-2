package eu.nordtal.season.messages.feedback;

/**
 * The sound of one category.
 * The key is a namespaced registry key, not a Bukkit enum constant, so a sound needs no code.
 *
 * @param key    the namespaced sound key, such as {@code minecraft:ui.button.click}
 * @param volume {@code 1.0} is the level of the sound itself. A higher value widens only the radius
 *     in which others hear it.
 * @param pitch  {@code 1.0} leaves the sound unchanged. The client clamps it to 0.5 to 2.0.
 */
public record FeedbackSound(String key, float volume, float pitch) {

    public static final float DEFAULT_VOLUME = 1.0f;

    public static final float DEFAULT_PITCH = 1.0f;

    public static final float MIN_PITCH = 0.5f;

    public static final float MAX_PITCH = 2.0f;

    public FeedbackSound {
        if (key == null) {
            throw new IllegalArgumentException("a FeedbackSound needs a key; use FeedbackSounds to "
                    + "express 'this category is silent' instead");
        }
    }
}
