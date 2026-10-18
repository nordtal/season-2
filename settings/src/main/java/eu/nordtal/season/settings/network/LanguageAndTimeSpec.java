package eu.nordtal.season.settings.network;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

@ConfigSpec
public interface LanguageAndTimeSpec {

    /** Defaults to {@code en}, the only complete language. */
    @Order(1)
    @Name("Default language")
    @Key("default-language")
    @NoExplanationNeeded
    default String defaultLanguage() {
        return Locales.DEFAULT_TAG;
    }

    /** Language tags in lower case. A change applies at the next start. */
    @Order(2)
    @Name("Languages")
    @Key("languages")
    @NoExplanationNeeded
    default List<String> languages() {
        return List.of(Locales.DEFAULT_TAG, "de");
    }

    /** An IANA name such as {@code Europe/Berlin}. A change applies at the next start. */
    @Order(3)
    @Name("Default time zone")
    @Key("default-time-zone")
    @NoExplanationNeeded
    default String defaultTimeZone() {
        return "Europe/Berlin";
    }
}
