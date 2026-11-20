package eu.nordtal.season.settings.network;

import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code npc-dialogue} group holds how an NPC's dialogue types, sounds, zooms and ends on every Paper server. */
@ConfigSpec
public interface NpcDialogueSpec {

    @Order(1)
    @Name("Typing speed (characters per second)")
    @Key("typing-speed")
    @Positive
    @NoExplanationNeeded
    default int typingSpeed() {
        return 30;
    }

    @Order(2)
    @Name("Zoom")
    @Key("zoom")
    @Positive
    @Explain("How far the view narrows while a dialogue runs: 1 keeps it as it is, 0.8 narrows it a little.")
    default double zoom() {
        return 0.8;
    }

    @Order(3)
    @Name("End distance (blocks)")
    @Key("end-distance")
    @Positive
    @Explain("A dialogue ends once the player walks this far beyond where they stood when it began.")
    default double endDistance() {
        return 3.0;
    }

    @Order(4)
    @Name("End angle (degrees)")
    @Key("end-angle")
    @Positive
    @Explain("A dialogue ends once the player turns this far away from the NPC.")
    default double endAngle() {
        return 120.0;
    }

    @Order(5)
    @Name("Typing tick pitch")
    @Key("tick-pitch")
    @Positive
    @Explain("The pitch of the tick while an NPC types, times each NPC's own voice pitch: 1 keeps the voice as it is,"
            + " 1.2 makes every NPC tick a little higher. From 0.5 to 2.")
    default double tickPitch() {
        return 1.0;
    }
}
