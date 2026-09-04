package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.Specs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ten sounds a fresh {@code sounds.yml} is written with.
 *
 * <p>Same mechanism and same caveat as {@link DefaultSmp}: a nested spec is served by a reflective
 * proxy and {@code Specs.createUnsafe} does <b>not</b> apply defaults, so every key of the spec has
 * to appear in the map or it comes out null.
 *
 * <p>Everything here is a proposal that nobody has heard next to anything else. {@code SoundsSpec}
 * carries the reasoning; this class is only the values.
 */
final class DefaultSounds {

    // Ten vanilla sounds, one per Feedback category. EVERY KEY BELOW WAS RESOLVED against Bukkit's
    // own sound list as compiled for paper-api 26.2.build.121-stable on 2026-09-04, not taken from
    // memory - SoundDefaultsTest re-resolves all ten on every build, so a Minecraft release that
    // retires one turns the build red rather than turning a chime silent.
    //
    // The pitches are the only part chosen rather than named: they are what separates two categories
    // that share a family. Retuning any of them is a config edit.

    /** A pickup, pitched up so it reads as lighter than the level-up. */
    static final SoundsSpec.SoundSpec SMALL_SUCCESS = sound("minecraft:entity.experience_orb.pickup", 1.4f);

    /** The level-up chime - the game's own sound for "that took a while". */
    static final SoundsSpec.SoundSpec BIG_SUCCESS = sound("minecraft:entity.player.levelup", 1.0f);

    /** A low note block. Short, unmistakably negative, and not the villager's groan. */
    static final SoundsSpec.SoundSpec REFUSED = sound("minecraft:block.note_block.bass", 0.7f);

    /** The villager's "no", which everybody already reads as having lost something. */
    static final SoundsSpec.SoundSpec LOSS = sound("minecraft:entity.villager.no", 0.9f);

    static final SoundsSpec.SoundSpec SURFACE_OPEN = sound("minecraft:block.barrel.open", 1.2f);

    static final SoundsSpec.SoundSpec SURFACE_CLOSE = sound("minecraft:block.barrel.close", 1.2f);

    /** The vanilla UI click, because a menu should sound like the game's own menus. */
    static final SoundsSpec.SoundSpec SELECT = sound("minecraft:ui.button.click", 1.0f);

    static final SoundsSpec.SoundSpec TRAVEL = sound("minecraft:block.beacon.power_select", 1.0f);

    /** A hi-hat: short enough to fire four times in four seconds without becoming noise. */
    static final SoundsSpec.SoundSpec COUNTDOWN_TICK = sound("minecraft:block.note_block.hat", 1.0f);

    /** The advancement toast, which is the one sound vanilla itself uses to mean "look". */
    static final SoundsSpec.SoundSpec NETWORK_EVENT = sound("minecraft:ui.toast.challenge_complete", 1.0f);

    private DefaultSounds() {
    }

    private static SoundsSpec.SoundSpec sound(final String key, final float pitch) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("key", key);
        values.put("volume", 1.0f);
        values.put("pitch", pitch);
        return Specs.createUnsafe(SoundsSpec.SoundSpec.class, values);
    }

}
