package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code sounds.yml} - what each feedback category sounds like on the event server.
 *
 * <h2>Why this is a file of its own and not a block in {@code config.yml}</h2>
 * The same argument {@code smp}'s {@code SoundsSpec} makes, and it is if anything stronger here.
 * {@code config.yml} holds the border schedule, the loot timings and the spawn towers, and
 * {@code /hg reload} deliberately re-reads none of them: a game is a running clock, and re-reading
 * a border parameter mid-match would move a shrink that players are already running from. A sound
 * is the one thing in this module that an operator iterates on <b>by ear</b>, with players online,
 * and an escape hatch that costs a restart of the event is worth very little.
 *
 * <h2>Three of the ten are read and never played, on purpose</h2>
 * This module has no menus - the whole interface is chat, three boss bars and a title - so nothing
 * here plays {@code surface-open}, {@code surface-close} or {@code select}. They are still declared,
 * because the adapter's exhaustive {@code switch} is what stops a category being added to
 * {@link eu.nordtal.s2.common.feedback.Feedback} without somebody saying what it sounds like
 * <em>everywhere</em>. The header below says so in the file itself, so that nobody spends an evening
 * retuning a key nothing reaches.
 *
 * <p>Nine categories, ten entries - open and close are the two halves of one. A call site in the
 * plugin picks a category and nothing else; {@code SoundVocabularyTest} in {@code :common} fails the
 * build if one ever names a sound. docs/presentation.md section 4 is the concept.
 */
@ConfigSpec(header = {
        "hunger-games - sounds",
        "",
        "What each feedback category sounds like. Nine categories, ten entries - open and close are",
        "the two halves of one - and a call site in the plugin can pick a category and nothing else.",
        "That is a structural rule and not a matter of discipline: a codebase where every call site",
        "names its own sound drifts into nine different chimes for the same kind of event.",
        "docs/presentation.md section 4 is the concept, and every value below is deliberately the",
        "same as the SMP's, so that the network sounds like one server rather than three.",
        "",
        "THREE OF THESE ARE NEVER PLAYED ON THIS SERVER: surface-open, surface-close and select.",
        "The event server has no menus - chat, three boss bars and a title are the whole interface -",
        "so those three are read and nothing reaches them. They are still here because the plugin",
        "has to answer for every category or it does not compile, which is what keeps this file and",
        "the code from drifting apart.",
        "",
        "A KEY IS A NAMESPACED REGISTRY KEY, NOT A BUKKIT CONSTANT: minecraft:ui.button.click, never",
        "UI_BUTTON_CLICK. The constant names change between Minecraft versions and the registry keys",
        "do not - and a key can name a sound out of our own resource pack, which is how a custom",
        "chime arrives later without a line of Java changing.",
        "",
        "AN EMPTY KEY SILENCES THAT CATEGORY. That is the escape hatch for a sound that turns out to",
        "be irritating with twenty people on spawn towers, and it works everywhere in this plugin at",
        "once rather than at fifteen call sites. This file is separate from config.yml precisely so",
        "that /hg reload can pick the change up in the middle of a game - config.yml is not re-read",
        "there, because a border parameter must not move while people are running from it.",
        "",
        "A key that is not a parseable namespaced key is reported in the console and silences its",
        "category; it never stops the server. A key that parses but names no sound simply plays",
        "nothing, which is exactly what a pack sound does before the pack is installed.",
        "",
        "Volume 1.0 is the sound's own level - above 1 does not get louder, it widens the radius",
        "other players hear it from. Pitch is playback speed and the client clamps it to 0.5 - 2.0.",
        "",
        "Every key below was resolved against Bukkit's own sound list as compiled for Paper 26.2",
        "build 121 on 2026-09-04, and SoundDefaultsTest keeps doing so on every build."
})
public interface SoundsSpec {

    @Order(1) @Key("small-success")
    @Comment("Something small went right: a kill, or your team marked ready.")
    default SoundSpec smallSuccess() { return DefaultSounds.SMALL_SUCCESS; }

    @Order(2) @Key("big-success")
    @Comment("You won the game. Heard by exactly one player, once per event.")
    default SoundSpec bigSuccess() { return DefaultSounds.BIG_SUCCESS; }

    @Order(3) @Key("refused")
    @Comment("The server said no: not an admin, no game, too few participants, not registered.")
    default SoundSpec refused() { return DefaultSounds.REFUSED; }

    @Order(4) @Key("loss")
    @Comment("You were eliminated. The one sound in this file every player expects to hear.")
    default SoundSpec loss() { return DefaultSounds.LOSS; }

    @Order(5) @Key("surface-open")
    @Comment("Never played here - this server has no menus. Kept so the vocabulary stays whole.")
    default SoundSpec surfaceOpen() { return DefaultSounds.SURFACE_OPEN; }

    @Order(6) @Key("surface-close")
    @Comment("Never played here, for the same reason as surface-open.")
    default SoundSpec surfaceClose() { return DefaultSounds.SURFACE_CLOSE; }

    @Order(7) @Key("select")
    @Comment("Never played here - nothing on this server is picked out of a list.")
    default SoundSpec select() { return DefaultSounds.SELECT; }

    @Order(8) @Key("travel")
    @Comment("Going somewhere: being placed on your spawn tower when the game starts.")
    default SoundSpec travel() { return DefaultSounds.TRAVEL; }

    @Order(9) @Key("countdown-tick")
    @Comment("A clock running out: the lobby countdown, the release, and every border shrink.")
    default SoundSpec countdownTick() { return DefaultSounds.COUNTDOWN_TICK; }

    @Order(10) @Key("network-event")
    @Comment("Everybody hears it: a loot refill, the same-team warning, somebody else winning.")
    default SoundSpec networkEvent() { return DefaultSounds.NETWORK_EVENT; }

    /** One sound: the key, how loud, how fast. */
    @ConfigSpec
    interface SoundSpec {

        // No @Comment: this interface is written out ten times over, and ten copies of the same
        // sentence is what turns a config file into something nobody reads. The header above says
        // what a key is and what an empty one does.
        @Order(1) @Key("key")
        default String key() { return ""; }

        @Order(2) @Key("volume") default float volume() { return 1.0f; }

        @Order(3) @Key("pitch") default float pitch() { return 1.0f; }
    }
}
