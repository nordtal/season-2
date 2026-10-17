package eu.nordtal.season.settings;

import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code colours} group: the {@link Tone} colours every text is painted with; a tone's default is its own. */
@ConfigSpec
public interface ColoursSpec {

    /** For a reply that arrives: it worked, is current, or came back. */
    @Order(1)
    @Name("Good")
    @Key("good")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String good() {
        return Tone.GOOD.hex();
    }

    /** For a reply that fails. Needs to stand out in a long list. */
    @Order(2)
    @Name("Bad")
    @Key("bad")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String bad() {
        return Tone.BAD.hex();
    }

    /** Not a failure, but not what was asked for either: stopped, too late, still waiting. */
    @Order(3)
    @Name("Warning")
    @Key("warn")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String warn() {
        return Tone.WARN.hex();
    }

    /** An ordinary reply with nothing to flag. */
    @Order(4)
    @Name("Neutral")
    @Key("neutral")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String neutral() {
        return Tone.NEUTRAL.hex();
    }

    /** Supporting detail under a line that already carries the news. */
    @Order(5)
    @Name("Muted")
    @Key("muted")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String muted() {
        return Tone.MUTED.hex();
    }

    /** A heading, a title, an icon that opens a line. */
    @Order(6)
    @Name("Accent")
    @Key("accent")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String accent() {
        return Tone.ACCENT.hex();
    }

    /** The network's own name and links. */
    @Order(7)
    @Name("Brand")
    @Key("brand")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String brand() {
        return Tone.BRAND.hex();
    }

    /** The word in a line that matters most: a name, a number, a place. */
    @Order(8)
    @Name("Emphasis")
    @Key("emphasis")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String emphasis() {
        return Tone.EMPHASIS.hex();
    }

    /** Barely there: a hint, a rule, a separator. */
    @Order(9)
    @Name("Faint")
    @Key("faint")
    @NoExplanationNeeded
    @Refers(Refers.To.COLOUR)
    default String faint() {
        return Tone.FAINT.hex();
    }
}
