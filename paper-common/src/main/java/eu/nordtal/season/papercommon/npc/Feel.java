package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.settings.network.NpcDialogueSpec;

/** How a dialogue types, sounds, zooms and ends, from the network's settings; the tick pitch scales every voice. */
public record Feel(int typingSpeed, double tickPitch, double zoom, double endDistance, double endAngle) {

    public Feel {
        if (typingSpeed <= 0) {
            throw new IllegalArgumentException("a typing speed is above 0, not " + typingSpeed);
        }
    }

    public static Feel of(final NpcDialogueSpec spec) {
        return new Feel(spec.typingSpeed(), spec.tickPitch(), spec.zoom(), spec.endDistance(), spec.endAngle());
    }
}
