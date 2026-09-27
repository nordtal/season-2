package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** {@code sounds.yml}: what each feedback category sounds like, reloadable mid-game, since it is tuned by ear. */
@ConfigSpec(
        header = {
            "hunger-games: sounds",
            "",
            "What each feedback category sounds like. The plugin plays categories, never a sound of",
            "its own, so one change here reaches every call site. The values match the SMP's.",
            "",
            "NEVER PLAYED HERE: surface-open, surface-close, select and reclaimed. This server has no",
            "menus and no graves, but every category must be answered or the plugin does not compile.",
            "",
            "A KEY IS A NAMESPACED REGISTRY KEY, NOT A BUKKIT CONSTANT: minecraft:ui.button.click, never",
            "UI_BUTTON_CLICK. A key may name a sound from our own resource pack.",
            "",
            "AN EMPTY KEY SILENCES THAT CATEGORY, and /hg reload picks the change up mid-game.",
            "An unparseable key is reported in the console and silences its category; it never stops the",
            "server. A key that names no sound plays nothing, as a pack sound does before the pack is in.",
            "",
            "Volume above 1 does not get louder, it widens the radius other players hear it from.",
            "Pitch is playback speed; the client clamps it to 0.5 to 2.0."
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
    @Comment("You were eliminated.")
    @Explain("You were eliminated.")
    default SoundSpec loss() {
        return DefaultSounds.LOSS;
    }

    @Order(5)
    @Name("Surface open")
    @Key("surface-open")
    @Comment("Never played here: this server has no menus.")
    @Explain("Never played on this server.")
    default SoundSpec surfaceOpen() {
        return DefaultSounds.SURFACE_OPEN;
    }

    @Order(6)
    @Name("Surface close")
    @Key("surface-close")
    @Comment("Never played here, for the same reason as surface-open.")
    @Explain("Never played on this server.")
    default SoundSpec surfaceClose() {
        return DefaultSounds.SURFACE_CLOSE;
    }

    @Order(7)
    @Name("Select")
    @Key("select")
    @Comment("Never played here: nothing on this server is picked out of a list.")
    @Explain("Never played on this server.")
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
        "A staged moment, such as the season's opening on a player's first join.",
        "SHIPS EMPTY: the sound arrives in the resource pack with its artwork."
    })
    @Explain("A staged moment, such as the season's opening. Ships empty until the resource pack has the sound.")
    default SoundSpec staging() {
        return DefaultSounds.STAGING;
    }

    @Order(12)
    @Name("Reclaimed")
    @Key("reclaimed")
    @Comment("Never played here: this server has no graves.")
    @Explain("Never played on this server.")
    default SoundSpec reclaimed() {
        return DefaultSounds.RECLAIMED;
    }

    /** One sound: the key, how loud, how fast. */
    @ConfigSpec
    interface SoundSpec {

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
