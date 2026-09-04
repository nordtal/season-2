package eu.nordtal.s2.common.feedback;

/**
 * One category's sound, as three plain values.
 *
 * <p><b>The sound is a namespaced registry key, never an enum constant.</b>
 * {@code minecraft:entity.player.levelup}, not {@code ENTITY_PLAYER_LEVELUP}. Two reasons, and both
 * of them are about the next five years rather than about today:
 *
 * <ul>
 *   <li>Bukkit's {@code Sound} constants are generated from the registry and are explicitly
 *       documented as removable between versions ("you should not depend on the ordinal values of
 *       this class"). The registry key is the stable identifier - it is what the resource pack, the
 *       vanilla {@code /playsound} command and the network protocol all use.</li>
 *   <li>A key can name a sound the server has never heard of. That is how a custom sound out of
 *       {@code resource-pack/} arrives later without a line of Java changing.</li>
 * </ul>
 *
 * <p>Nothing here is a Bukkit type, because {@code :common} is compiled against neither Paper nor
 * Velocity. Turning these three values into a sound a client hears is a platform adapter's job.
 *
 * @param key    the namespaced sound key, e.g. {@code minecraft:ui.button.click}
 * @param volume how loud, {@code 1.0} being the sound's own level. Above 1 does not get louder; it
 *               widens the radius other players hear it from, which is why every value in this
 *               repository is 1.0 or below
 * @param pitch  playback speed, 1.0 being unchanged. The client clamps this to 0.5 - 2.0
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
