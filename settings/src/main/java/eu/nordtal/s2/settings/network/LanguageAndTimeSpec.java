package eu.nordtal.s2.settings.network;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;
import java.util.List;

/** The languages the network speaks and the zone it tells time in, the defaults of a reader without their own. */
@ConfigSpec(header = "network: the languages the network speaks and the zone it tells time in")
public interface LanguageAndTimeSpec {

    @Order(1)
    @Name("Default language")
    @Key("default-language")
    @Comment({
        "What a reader with no language of their own is shown. It is en: only the English",
        "bundles are complete, and every missing translation falls back to them."
    })
    @Explain("What a reader with no language of their own is shown; en, since only English is complete.")
    default String defaultLanguage() {
        return "en";
    }

    @Order(2)
    @Name("Languages")
    @Key("languages")
    @Comment({
        "Every language the network speaks, the default among them, as lower case tags.",
        "A language without a bundle falls back to English key by key. Taken at the next start."
    })
    @Explain("Every language the network speaks, as lower case tags; taken at the next start.")
    default List<String> languages() {
        return List.of("en", "de");
    }

    @Order(3)
    @Name("Default time zone")
    @Key("default-time-zone")
    @Comment({
        "The zone a reader with no zone of their own reads a date in, and the one an admin types",
        "a date in: an IANA name such as Europe/Berlin. Taken at the next start."
    })
    @Explain("The zone dates are shown and typed in, as an IANA name like Europe/Berlin; taken at the next start.")
    default String defaultTimeZone() {
        return "Europe/Berlin";
    }
}
