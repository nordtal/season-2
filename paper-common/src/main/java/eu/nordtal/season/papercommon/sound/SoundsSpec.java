package eu.nordtal.season.papercommon.sound;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** What each feedback category sounds like on one server, taken while it runs, since it is tuned by ear. */
@ConfigSpec
public interface SoundsSpec {

    /** Something small went right: an objective handed in, a kill, or a team marked ready. */
    @Order(1)
    @Name("Small success")
    @Key("small-success")
    @NoExplanationNeeded
    default SoundSpec smallSuccess() {
        return DefaultSounds.SMALL_SUCCESS;
    }

    /** Something that took work: a milestone finished, a duel or a game won, or a wheel prize. */
    @Order(2)
    @Name("Big success")
    @Key("big-success")
    @NoExplanationNeeded
    default SoundSpec bigSuccess() {
        return DefaultSounds.BIG_SUCCESS;
    }

    /** The server said no: spawn ground, not your POI, not registered, or nothing to show. */
    @Order(3)
    @Name("Refused")
    @Key("refused")
    @NoExplanationNeeded
    default SoundSpec refused() {
        return DefaultSounds.REFUSED;
    }

    /** Something was taken: a duel lost, aura lost to a death, or eliminated from a game. */
    @Order(4)
    @Name("Loss")
    @Key("loss")
    @NoExplanationNeeded
    default SoundSpec loss() {
        return DefaultSounds.LOSS;
    }

    /** A menu or a grave opened. */
    @Order(5)
    @Name("Surface open")
    @Key("surface-open")
    @NoExplanationNeeded
    default SoundSpec surfaceOpen() {
        return DefaultSounds.SURFACE_OPEN;
    }

    /** A menu or a grave closed. */
    @Order(6)
    @Name("Surface close")
    @Key("surface-close")
    @NoExplanationNeeded
    default SoundSpec surfaceClose() {
        return DefaultSounds.SURFACE_CLOSE;
    }

    /** A click that picked something: a menu entry, or a duel platform stepped onto. */
    @Order(7)
    @Name("Select")
    @Key("select")
    @NoExplanationNeeded
    default SoundSpec select() {
        return DefaultSounds.SELECT;
    }

    /** Going somewhere: the balloon, the duel arena, a spawn tower. */
    @Order(8)
    @Name("Travel")
    @Key("travel")
    @NoExplanationNeeded
    default SoundSpec travel() {
        return DefaultSounds.TRAVEL;
    }

    /** One tick of a clock running out: a duel start, a game countdown, a border shrink. */
    @Order(9)
    @Name("Countdown tick")
    @Key("countdown-tick")
    @NoExplanationNeeded
    default SoundSpec countdownTick() {
        return DefaultSounds.COUNTDOWN_TICK;
    }

    /** Heard by everyone: a milestone finished or a game won by somebody else, or a loot refill. */
    @Order(10)
    @Name("Network event")
    @Key("network-event")
    @NoExplanationNeeded
    default SoundSpec networkEvent() {
        return DefaultSounds.NETWORK_EVENT;
    }

    /** A staged moment, such as the season's opening. Ships empty until the resource pack has the sound. */
    @Order(11)
    @Name("Staging")
    @Key("staging")
    @NoExplanationNeeded
    default SoundSpec staging() {
        return DefaultSounds.STAGING;
    }

    /** A grave settling once emptied. Heard by everyone standing nearby, not only the looter. */
    @Order(12)
    @Name("Reclaimed")
    @Key("reclaimed")
    @NoExplanationNeeded
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
