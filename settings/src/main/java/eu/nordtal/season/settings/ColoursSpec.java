package eu.nordtal.season.settings;

import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code colours} group: the colour of each {@link Tone}. */
@ConfigSpec
public interface ColoursSpec {

    /** Colours a reply that worked, is current or came back. */
    @Order(1)
    @Name("Good")
    @Key("good")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String good() {
        return Tone.GOOD.hex();
    }

    /** Colours a reply that fails. It must stand out in a long list. */
    @Order(2)
    @Name("Bad")
    @Key("bad")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String bad() {
        return Tone.BAD.hex();
    }

    /** Colours a reply that is no failure but not what was asked for: stopped, too late or still waiting. */
    @Order(3)
    @Name("Warning")
    @Key("warn")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String warn() {
        return Tone.WARN.hex();
    }

    @Order(4)
    @Name("Neutral")
    @Key("neutral")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String neutral() {
        return Tone.NEUTRAL.hex();
    }

    /** Colours detail under a line that already states the result. */
    @Order(5)
    @Name("Muted")
    @Key("muted")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String muted() {
        return Tone.MUTED.hex();
    }

    /** Colours a heading, a title or an icon that opens a line. */
    @Order(6)
    @Name("Accent")
    @Key("accent")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String accent() {
        return Tone.ACCENT.hex();
    }

    @Order(7)
    @Name("Brand")
    @Key("brand")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String brand() {
        return Tone.BRAND.hex();
    }

    /** Colours the most important word of a line: a name, a number or a place. */
    @Order(8)
    @Name("Emphasis")
    @Key("emphasis")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String emphasis() {
        return Tone.EMPHASIS.hex();
    }

    @Order(9)
    @Name("Faint")
    @Key("faint")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String faint() {
        return Tone.FAINT.hex();
    }
}
