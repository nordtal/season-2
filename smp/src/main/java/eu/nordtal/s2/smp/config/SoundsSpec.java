package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code sounds.yml} - what each feedback category sounds like.
 *
 * <p>A file of its own rather than a block in {@code config.yml} because it is the one config an
 * operator iterates on <b>by ear</b>, with players online: {@code /smp reload} re-reads it, while
 * {@code config.yml} is deliberately not reloadable.
 *
 * <p>Nine categories, ten entries - open and close are the two halves of one. A call site picks a
 * category and nothing else; {@code SoundVocabularyTest} in {@code :common} fails the build if one
 * ever names a sound directly.
 */
@ConfigSpec(header = {
        "smp - sounds",
        "",
        "What each feedback category sounds like. Nine categories, ten entries - open and close are",
        "the two halves of one - and a call site in the plugin can pick a category and nothing else.",
        "That is a structural rule: a codebase where every call site names its own sound drifts",
        "into nine different chimes for the same kind of event.",
        "",
        "A KEY IS A NAMESPACED REGISTRY KEY, NOT A BUKKIT CONSTANT: minecraft:ui.button.click, never",
        "UI_BUTTON_CLICK. The constant names change between Minecraft versions and the registry keys",
        "do not - and a key can name a sound out of our own resource pack, which is how a custom",
        "chime arrives later without a line of Java changing.",
        "",
        "AN EMPTY KEY SILENCES THAT CATEGORY. That is the escape hatch for a sound that turns out to",
        "be irritating with twenty people in a tavern, and it works everywhere in this plugin at",
        "once rather than at thirteen call sites. This file is separate from config.yml precisely so",
        "that /smp reload can pick the change up without restarting the season.",
        "",
        "A key that is not a parseable namespaced key is reported in the console and silences its",
        "category; it never stops the server. A key that parses but names no sound simply plays",
        "nothing, which is exactly what a pack sound does before the pack is installed.",
        "",
        "Volume 1.0 is the sound's own level - above 1 does not get louder, it widens the radius",
        "other players hear it from. Pitch is playback speed and the client clamps it to 0.5 - 2.0.",
        "",
        "SoundDefaultsTest resolves every key below against Bukkit's own sound list on every",
        "build."
})
public interface SoundsSpec {

    @Order(1) @Key("small-success")
    @Comment("Something small went right: an objective handed in, a POI created or removed.")
    default SoundSpec smallSuccess() { return DefaultSounds.SMALL_SUCCESS; }

    @Order(2) @Key("big-success")
    @Comment("Something that took work: a milestone you finished, a duel won, a wheel prize.")
    default SoundSpec bigSuccess() { return DefaultSounds.BIG_SUCCESS; }

    @Order(3) @Key("refused")
    @Comment("The server said no: spawn ground, not your POI, no spin left, nothing to show.")
    default SoundSpec refused() { return DefaultSounds.REFUSED; }

    @Order(4) @Key("loss")
    @Comment("Something was taken: a duel lost, aura lost to a death, a spin spent on nothing.")
    default SoundSpec loss() { return DefaultSounds.LOSS; }

    @Order(5) @Key("surface-open")
    @Comment("A menu or a grave opened.")
    default SoundSpec surfaceOpen() { return DefaultSounds.SURFACE_OPEN; }

    @Order(6) @Key("surface-close")
    @Comment("The same surface closed.")
    default SoundSpec surfaceClose() { return DefaultSounds.SURFACE_CLOSE; }

    @Order(7) @Key("select")
    @Comment("A click that picked something: a menu entry, or a duel platform stepped onto.")
    default SoundSpec select() { return DefaultSounds.SELECT; }

    @Order(8) @Key("travel")
    @Comment("Going somewhere: the balloon, the farm reset moving you, the duel arena.")
    default SoundSpec travel() { return DefaultSounds.TRAVEL; }

    @Order(9) @Key("countdown-tick")
    @Comment("One tick of a clock running out: a duel start, a farm reset warning.")
    default SoundSpec countdownTick() { return DefaultSounds.COUNTDOWN_TICK; }

    @Order(10) @Key("network-event")
    @Comment("Everybody hears it: a milestone finished by somebody else.")
    default SoundSpec networkEvent() { return DefaultSounds.NETWORK_EVENT; }

    @Order(11) @Key("staging")
    @Comment({
            "A staged moment - today only the season's opening on a player's first join.",
            "",
            "SHIPS EMPTY, deliberately: a staged moment's sound arrives in the resource pack with",
            "its artwork and does not exist yet. An empty key is silence until then."
    })
    default SoundSpec staging() { return DefaultSounds.STAGING; }

    /** One sound: the key, how loud, how fast. */
    @ConfigSpec
    interface SoundSpec {

        // No @Comment: this interface is written out ten times over, and the header above already
        // says what a key is and what an empty one does.
        @Order(1) @Key("key")
        default String key() { return ""; }

        @Order(2) @Key("volume") default float volume() { return 1.0f; }

        @Order(3) @Key("pitch") default float pitch() { return 1.0f; }
    }
}
