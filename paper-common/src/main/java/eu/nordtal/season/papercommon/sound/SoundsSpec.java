package eu.nordtal.season.papercommon.sound;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** What each feedback category sounds like on one server, taken while it runs, since it is tuned by ear. */
@ConfigSpec
public interface SoundsSpec {

    @Order(1)
    @Name("Small success")
    @Key("small-success")
    @Explain("Something small went right: an objective handed in, a kill, or a team marked ready.")
    default SoundSpec smallSuccess() {
        return DefaultSounds.SMALL_SUCCESS;
    }

    @Order(2)
    @Name("Big success")
    @Key("big-success")
    @Explain("Something that took work: a milestone finished, a duel or a game won, or a wheel prize.")
    default SoundSpec bigSuccess() {
        return DefaultSounds.BIG_SUCCESS;
    }

    @Order(3)
    @Name("Refused")
    @Key("refused")
    @Explain("The server said no: spawn ground, not your POI, not registered, or nothing to show.")
    default SoundSpec refused() {
        return DefaultSounds.REFUSED;
    }

    @Order(4)
    @Name("Loss")
    @Key("loss")
    @Explain("Something was taken: a duel lost, aura lost to a death, or eliminated from a game.")
    default SoundSpec loss() {
        return DefaultSounds.LOSS;
    }

    @Order(5)
    @Name("Surface open")
    @Key("surface-open")
    @Explain("A menu or a grave opened.")
    default SoundSpec surfaceOpen() {
        return DefaultSounds.SURFACE_OPEN;
    }

    @Order(6)
    @Name("Surface close")
    @Key("surface-close")
    @Explain("A menu or a grave closed.")
    default SoundSpec surfaceClose() {
        return DefaultSounds.SURFACE_CLOSE;
    }

    @Order(7)
    @Name("Select")
    @Key("select")
    @Explain("A click that picked something: a menu entry, or a duel platform stepped onto.")
    default SoundSpec select() {
        return DefaultSounds.SELECT;
    }

    @Order(8)
    @Name("Travel")
    @Key("travel")
    @Explain("Going somewhere: the balloon, the duel arena, a spawn tower.")
    default SoundSpec travel() {
        return DefaultSounds.TRAVEL;
    }

    @Order(9)
    @Name("Countdown tick")
    @Key("countdown-tick")
    @Explain("One tick of a clock running out: a duel start, a game countdown, a border shrink.")
    default SoundSpec countdownTick() {
        return DefaultSounds.COUNTDOWN_TICK;
    }

    @Order(10)
    @Name("Network event")
    @Key("network-event")
    @Explain("Heard by everyone: a milestone finished or a game won by somebody else, or a loot refill.")
    default SoundSpec networkEvent() {
        return DefaultSounds.NETWORK_EVENT;
    }

    @Order(11)
    @Name("Staging")
    @Key("staging")
    @Explain("A staged moment, such as the season's opening. Ships empty until the resource pack has the sound.")
    default SoundSpec staging() {
        return DefaultSounds.STAGING;
    }

    @Order(12)
    @Name("Reclaimed")
    @Key("reclaimed")
    @Explain("A grave settling once emptied. Heard by everyone standing nearby, not only the looter.")
    default SoundSpec reclaimed() {
        return DefaultSounds.RECLAIMED;
    }

    /** One sound: the key, how loud, how fast. */
    @ConfigSpec
    interface SoundSpec {

        // No        @Name("Sound")
        @Key("key")
        @NoExplanationNeeded
        @Refers(Refers.To.SOUND_EVENT)
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
