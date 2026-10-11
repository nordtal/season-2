package eu.nordtal.season.settings.network;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The languages the network speaks and the zone it tells time in, the defaults of a reader without their own. */
@ConfigSpec
public interface LanguageAndTimeSpec {

    /** en, because only English is complete. */
    @Order(1)
    @Name("Default language")
    @Key("default-language")
    @Explain("Shown to a reader with no language of their own.")
    default String defaultLanguage() {
        return Locales.DEFAULT_TAG;
    }

    @Order(2)
    @Name("Languages")
    @Key("languages")
    @Explain("Lower case tags. Taken at the next start.")
    default List<String> languages() {
        return List.of(Locales.DEFAULT_TAG, "de");
    }

    @Order(3)
    @Name("Default time zone")
    @Key("default-time-zone")
    @Explain("An IANA name like Europe/Berlin. Taken at the next start.")
    default String defaultTimeZone() {
        return "Europe/Berlin";
    }
}
