package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** {@code sounds.yml}: what each feedback category sounds like, reloadable, since it is tuned by ear. */
@ConfigSpec(
        header = {
            "smp: sounds",
            "",
            "What each feedback category sounds like. The plugin plays categories, never a sound of",
            "its own, so one change here reaches every call site. Open and close share one category.",
            "",
            "A KEY IS A NAMESPACED REGISTRY KEY, NOT A BUKKIT CONSTANT: minecraft:ui.button.click, never",
            "UI_BUTTON_CLICK. A key may name a sound from our own resource pack.",
            "",
            "AN EMPTY KEY SILENCES THAT CATEGORY, and /smp reload picks the change up without a restart.",
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
    @Comment("Something small went right: an objective handed in, a POI created or removed.")
    @Explain("Something small went right: an objective handed in, or a POI created or removed.")
    default SoundSpec smallSuccess() {
        return DefaultSounds.SMALL_SUCCESS;
    }

    @Order(2)
    @Name("Big success")
    @Key("big-success")
    @Comment("Something that took work: a milestone you finished, a duel won, a wheel prize.")
    @Explain("Something that took work: a milestone finished, a duel won, or a wheel prize.")
    default SoundSpec bigSuccess() {
        return DefaultSounds.BIG_SUCCESS;
    }

    @Order(3)
    @Name("Refused")
    @Key("refused")
    @Comment("The server said no: spawn ground, not your POI, no spin left, nothing to show.")
    @Explain("The server said no: spawn ground, not your POI, no spin left, or nothing to show.")
    default SoundSpec refused() {
        return DefaultSounds.REFUSED;
    }

    @Order(4)
    @Name("Loss")
    @Key("loss")
    @Comment("Something was taken: a duel lost, aura lost to a death, a spin spent on nothing.")
    @Explain("Something was taken: a duel lost, aura lost to a death, or a spin spent on nothing.")
    default SoundSpec loss() {
        return DefaultSounds.LOSS;
    }

    @Order(5)
    @Name("Surface open")
    @Key("surface-open")
    @Comment("A menu or a grave opened.")
    @Explain("A menu or a grave opened.")
    default SoundSpec surfaceOpen() {
        return DefaultSounds.SURFACE_OPEN;
    }

    @Order(6)
    @Name("Surface close")
    @Key("surface-close")
    @Comment("The same surface closed.")
    @Explain("A menu or a grave closed.")
    default SoundSpec surfaceClose() {
        return DefaultSounds.SURFACE_CLOSE;
    }

    @Order(7)
    @Name("Select")
    @Key("select")
    @Comment("A click that picked something: a menu entry, or a duel platform stepped onto.")
    @Explain("A click that picked something: a menu entry, or a duel platform stepped onto.")
    default SoundSpec select() {
        return DefaultSounds.SELECT;
    }

    @Order(8)
    @Name("Travel")
    @Key("travel")
    @Comment("Going somewhere: the balloon, or the duel arena.")
    @Explain("Going somewhere: the balloon, or the duel arena.")
    default SoundSpec travel() {
        return DefaultSounds.TRAVEL;
    }

    @Order(9)
    @Name("Countdown tick")
    @Key("countdown-tick")
    @Comment("One tick of a clock running out: a duel start.")
    @Explain("One tick of a clock running out: a duel start.")
    default SoundSpec countdownTick() {
        return DefaultSounds.COUNTDOWN_TICK;
    }

    @Order(10)
    @Name("Network event")
    @Key("network-event")
    @Comment("Everybody hears it: a milestone finished by somebody else.")
    @Explain("Heard by everyone: a milestone finished by somebody else.")
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
    @Comment("A grave settling once it is empty. A WORLD sound at the grave, heard by everyone nearby.")
    @Explain("A grave settling once emptied. Heard by everyone standing nearby, not only the looter.")
    default SoundSpec reclaimed() {
        return DefaultSounds.RECLAIMED;
    }

    /** One sound: the key, how loud, how fast. */
    @ConfigSpec
    interface SoundSpec {

        // No @Comment: written out ten times, and the header says what a key does.
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
