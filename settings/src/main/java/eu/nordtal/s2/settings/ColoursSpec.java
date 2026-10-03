package eu.nordtal.s2.settings;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.s2.messages.Tone;

/** The {@code colours} group: the {@link Tone} colours every text is painted with; a tone's default is its own. */
@ConfigSpec
public interface ColoursSpec {

    @Order(1)
    @Name("Good")
    @Key("good")
    @Comment("Arriving: it worked, it is current, it came back.")
    @Explain("For a reply that arrives: it worked, is current, or came back.")
    @Refers(Refers.To.COLOUR)
    default String good() {
        return Tone.GOOD.hex();
    }

    @Order(2)
    @Name("Bad")
    @Key("bad")
    @Comment("Leaving: it failed. The one tone that has to stand out in a long list.")
    @Explain("For a reply that fails. Needs to stand out in a long list.")
    @Refers(Refers.To.COLOUR)
    default String bad() {
        return Tone.BAD.hex();
    }

    @Order(3)
    @Name("Warning")
    @Key("warn")
    @Comment("Not a failure, but not what was asked for either: stopped, too late, still waiting.")
    @Explain("Not a failure, but not what was asked for either: stopped, too late, still waiting.")
    @Refers(Refers.To.COLOUR)
    default String warn() {
        return Tone.WARN.hex();
    }

    @Order(4)
    @Name("Neutral")
    @Key("neutral")
    @Comment("An ordinary reply with nothing to flag. Lighter than muted, so the two stay distinguishable.")
    @Explain("An ordinary reply with nothing to flag.")
    @Refers(Refers.To.COLOUR)
    default String neutral() {
        return Tone.NEUTRAL.hex();
    }

    @Order(5)
    @Name("Muted")
    @Key("muted")
    @Comment("Supporting detail under a line that already carries the news.")
    @Explain("Supporting detail under a line that already carries the news.")
    @Refers(Refers.To.COLOUR)
    default String muted() {
        return Tone.MUTED.hex();
    }

    @Order(6)
    @Name("Accent")
    @Key("accent")
    @Comment("A heading, a title, an icon that opens a line.")
    @Explain("A heading, a title, an icon that opens a line.")
    @Refers(Refers.To.COLOUR)
    default String accent() {
        return Tone.ACCENT.hex();
    }

    @Order(7)
    @Name("Brand")
    @Key("brand")
    @Comment("The network's own name and links.")
    @Explain("The network's own name and links.")
    @Refers(Refers.To.COLOUR)
    default String brand() {
        return Tone.BRAND.hex();
    }

    @Order(8)
    @Name("Emphasis")
    @Key("emphasis")
    @Comment("The word in a line that matters most: a name, a number, a place.")
    @Explain("The word in a line that matters most: a name, a number, a place.")
    @Refers(Refers.To.COLOUR)
    default String emphasis() {
        return Tone.EMPHASIS.hex();
    }

    @Order(9)
    @Name("Faint")
    @Key("faint")
    @Comment("Barely there: a hint, a rule, a separator.")
    @Explain("Barely there: a hint, a rule, a separator.")
    @Refers(Refers.To.COLOUR)
    default String faint() {
        return Tone.FAINT.hex();
    }
}
