package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.spec.Specs;
import java.util.LinkedHashMap;
import java.util.Map;

/** The ten sounds a fresh {@code sounds.yml} is written with, as in {@code smp}; every key has to appear in the map. */
final class DefaultSounds {

    // One vanilla sound per Feedback category; SoundDefaultsTest resolves all ten.

    /** A pickup, pitched up so it reads as lighter than the level-up: a kill, a ready mark. */
    static final SoundsSpec.SoundSpec SMALL_SUCCESS = sound("minecraft:entity.experience_orb.pickup", 1.4f);

    /** The level-up chime, heard once by the winner. */
    static final SoundsSpec.SoundSpec BIG_SUCCESS = sound("minecraft:entity.player.levelup", 1.0f);

    /** A low note block: short, negative, and not the villager's groan. */
    static final SoundsSpec.SoundSpec REFUSED = sound("minecraft:block.note_block.bass", 0.7f);

    /** The villager's "no", which everybody already reads as having lost something. */
    static final SoundsSpec.SoundSpec LOSS = sound("minecraft:entity.villager.no", 0.9f);

    static final SoundsSpec.SoundSpec SURFACE_OPEN = sound("minecraft:block.barrel.open", 1.2f);

    static final SoundsSpec.SoundSpec SURFACE_CLOSE = sound("minecraft:block.barrel.close", 1.2f);

    static final SoundsSpec.SoundSpec SELECT = sound("minecraft:ui.button.click", 1.0f);

    static final SoundsSpec.SoundSpec TRAVEL = sound("minecraft:block.beacon.power_select", 1.0f);

    /** A hi-hat: short enough to fire nine times in a minute without becoming noise. */
    static final SoundsSpec.SoundSpec COUNTDOWN_TICK = sound("minecraft:block.note_block.hat", 1.0f);

    /** The advancement toast, which is the one sound vanilla itself uses to mean "look". */
    static final SoundsSpec.SoundSpec NETWORK_EVENT = sound("minecraft:ui.toast.challenge_complete", 1.0f);

    /** No sound, deliberately (see {@code Feedback.STAGING}); written anyway so the key appears in a fresh file. */
    static final SoundsSpec.SoundSpec STAGING = sound("", 1.0f);

    static final SoundsSpec.SoundSpec RECLAIMED = sound("minecraft:entity.skeleton.death", 1.0f);

    private DefaultSounds() {}

    private static SoundsSpec.SoundSpec sound(final String key, final float pitch) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("key", key);
        values.put("volume", 1.0f);
        values.put("pitch", pitch);
        return Specs.createUnsafe(SoundsSpec.SoundSpec.class, values);
    }
}
