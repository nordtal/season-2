package eu.nordtal.season.settings;

import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code colours} group: the {@link Tone} colours every text is painted with; a tone's default is its own. */
@ConfigSpec
public interface ColoursSpec {

    @Order(1)
    @Name("Good")
    @Key("good")
    @Explain("For a reply that arrives: it worked, is current, or came back.")
    @Refers(Refers.To.COLOUR)
    default String good() {
        return Tone.GOOD.hex();
    }

    @Order(2)
    @Name("Bad")
    @Key("bad")
    @Explain("For a reply that fails. Needs to stand out in a long list.")
    @Refers(Refers.To.COLOUR)
    default String bad() {
        return Tone.BAD.hex();
    }

    @Order(3)
    @Name("Warning")
    @Key("warn")
    @Explain("Not a failure, but not what was asked for either: stopped, too late, still waiting.")
    @Refers(Refers.To.COLOUR)
    default String warn() {
        return Tone.WARN.hex();
    }

    @Order(4)
    @Name("Neutral")
    @Key("neutral")
    @Explain("An ordinary reply with nothing to flag.")
    @Refers(Refers.To.COLOUR)
    default String neutral() {
        return Tone.NEUTRAL.hex();
    }

    @Order(5)
    @Name("Muted")
    @Key("muted")
    @Explain("Supporting detail under a line that already carries the news.")
    @Refers(Refers.To.COLOUR)
    default String muted() {
        return Tone.MUTED.hex();
    }

    @Order(6)
    @Name("Accent")
    @Key("accent")
    @Explain("A heading, a title, an icon that opens a line.")
    @Refers(Refers.To.COLOUR)
    default String accent() {
        return Tone.ACCENT.hex();
    }

    @Order(7)
    @Name("Brand")
    @Key("brand")
    @Explain("The network's own name and links.")
    @Refers(Refers.To.COLOUR)
    default String brand() {
        return Tone.BRAND.hex();
    }

    @Order(8)
    @Name("Emphasis")
    @Key("emphasis")
    @Explain("The word in a line that matters most: a name, a number, a place.")
    @Refers(Refers.To.COLOUR)
    default String emphasis() {
        return Tone.EMPHASIS.hex();
    }

    @Order(9)
    @Name("Faint")
    @Key("faint")
    @Explain("Barely there: a hint, a rule, a separator.")
    @Refers(Refers.To.COLOUR)
    default String faint() {
        return Tone.FAINT.hex();
    }
}
