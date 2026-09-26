package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code sounds.yml}.
 *
 * What each feedback category sounds like on the event server. Its own file, not a block in
 * {@code config.yml}, because {@code /hg reload} re-reads it mid-game: sounds are tuned by ear with
 * players online, while a border parameter must not move under them.
 *
 * {@code surface-open}, {@code surface-close} and {@code select} are read and never played - this
 * server has no menus - but stay declared so the adapter's exhaustive {@code switch} keeps every
 * {@link eu.nordtal.s2.common.feedback.Feedback} category answered for. A call site picks a category
 * and never a sound.
 */
@ConfigSpec(
        header = {
            "hunger-games - sounds",
            "",
            "What each feedback category sounds like. Ten categories, eleven entries - open and close are",
            "the two halves of one - and a call site in the plugin can pick a category and nothing else.",
            "That is a structural rule and not a matter of discipline: a codebase where every call site",
            "names its own sound drifts into nine different chimes for the same kind of event.",
            "Every value below is deliberately the same as the SMP's, so that the network sounds like",
            "one server rather than three.",
            "",
            "FOUR OF THESE ARE NEVER PLAYED ON THIS SERVER: surface-open, surface-close, select and",
            "reclaimed. The event server has no menus - chat, three boss bars and a title are the whole",
            "interface - and it has no graves either, so those four are read and nothing reaches them.",
            "They are still here because the plugin has to answer for every category or it does not",
            "compile, which is what keeps this file and the code from drifting apart.",
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
            "SoundDefaultsTest resolves every key below against Bukkit's own sound list on each build."
        })
public interface SoundsSpec {

    @Order(1)
    @Name("Small success")
    @Key("small-success")
    @Comment("Something small went right: a kill, or your team marked ready.")
    @Explain("Something small went right: a kill, or your team marked ready.")
    default SoundSpec smallSuccess() {
        return DefaultSounds.SMALL_SUCCESS;
    }

    @Order(2)
    @Name("Big success")
    @Key("big-success")
    @Comment("You won the game. Heard by exactly one player, once per event.")
    @Explain("You won the game. Heard by exactly one player, once per event.")
    default SoundSpec bigSuccess() {
        return DefaultSounds.BIG_SUCCESS;
    }

    @Order(3)
    @Name("Refused")
    @Key("refused")
    @Comment("The server said no: not an admin, no game, too few participants, not registered.")
    @Explain("The server said no: not an admin, no game, too few participants, or not registered.")
    default SoundSpec refused() {
        return DefaultSounds.REFUSED;
    }

    @Order(4)
    @Name("Loss")
    @Key("loss")
    @Comment("You were eliminated. The one sound in this file every player expects to hear.")
    @Explain("You were eliminated.")
    default SoundSpec loss() {
        return DefaultSounds.LOSS;
    }

    @Order(5)
    @Name("Surface open")
    @Key("surface-open")
    @Comment("Never played here - this server has no menus. Kept so the vocabulary stays whole.")
    @Explain("Never played on this server - kept only so every feedback category stays answered.")
    default SoundSpec surfaceOpen() {
        return DefaultSounds.SURFACE_OPEN;
    }

    @Order(6)
    @Name("Surface close")
    @Key("surface-close")
    @Comment("Never played here, for the same reason as surface-open.")
    @Explain("Never played on this server - kept only so every feedback category stays answered.")
    default SoundSpec surfaceClose() {
        return DefaultSounds.SURFACE_CLOSE;
    }

    @Order(7)
    @Name("Select")
    @Key("select")
    @Comment("Never played here - nothing on this server is picked out of a list.")
    @Explain("Never played on this server - kept only so every feedback category stays answered.")
    default SoundSpec select() {
        return DefaultSounds.SELECT;
    }

    @Order(8)
    @Name("Travel")
    @Key("travel")
    @Comment("Going somewhere: being placed on your spawn tower when the game starts.")
    @Explain("Being placed on your spawn tower when the game starts.")
    default SoundSpec travel() {
        return DefaultSounds.TRAVEL;
    }

    @Order(9)
    @Name("Countdown tick")
    @Key("countdown-tick")
    @Comment("A clock running out: the lobby countdown, the release, and every border shrink.")
    @Explain("A clock running out: the lobby countdown, the release, or a border shrink.")
    default SoundSpec countdownTick() {
        return DefaultSounds.COUNTDOWN_TICK;
    }

    @Order(10)
    @Name("Network event")
    @Key("network-event")
    @Comment("Everybody hears it: a loot refill, the same-team warning, somebody else winning.")
    @Explain("Heard by everyone: a loot refill, the same-team warning, or somebody else winning.")
    default SoundSpec networkEvent() {
        return DefaultSounds.NETWORK_EVENT;
    }

    @Order(11)
    @Name("Staging")
    @Key("staging")
    @Comment({
        "A staged moment - today only the season's opening on a player's first join.",
        "",
        "SHIPS EMPTY on purpose: a staged moment's sound arrives with the resource pack artwork",
        "and does not exist yet. Filling it in is one line here and no release."
    })
    @Explain(
            "A staged moment - today only the season's opening on a player's first join. Ships empty until the resource pack has the sound.")
    default SoundSpec staging() {
        return DefaultSounds.STAGING;
    }

    @Order(12)
    @Name("Reclaimed")
    @Key("reclaimed")
    @Comment("Never played here - this server has no graves. Kept so the vocabulary stays whole.")
    @Explain("Never played on this server - kept only so every feedback category stays answered.")
    default SoundSpec reclaimed() {
        return DefaultSounds.RECLAIMED;
    }

    /** One sound: the key, how loud, how fast. */
    @ConfigSpec
    interface SoundSpec {

        // No @Comment: written out ten times over, and the header above already says what a key is.
        @Order(1)
        @Name("Sound")
        @Key("key")
        @NoExplanationNeeded
        default String key() {
            return "";
        }

        @Order(2)
        @Name("Volume")
        @Key("volume")
        @NoExplanationNeeded
        default float volume() {
            return 1.0f;
        }

        @Order(3)
        @Name("Pitch")
        @Key("pitch")
        @NoExplanationNeeded
        default float pitch() {
            return 1.0f;
        }
    }
}
