package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.spec.Specs;
import java.util.List;
import java.util.Map;

/** The countries offered by the start channel until Steward changes the list. */
final class DefaultCountries {
    static final List<OnboardingSpec.CountrySpec> LIST = List.of(
            country("de", "Deutschland", "Europe/Berlin", "de", Mark.flag("DE"), "flag-germany"),
            country("at", "Österreich", "Europe/Vienna", "de", Mark.flag("AT"), "flag-austria"),
            country("ch", "Schweiz", "Europe/Zurich", "de", Mark.flag("CH"), "flag-switzerland"),
            country("nl", "Nederland", "Europe/Amsterdam", "en", Mark.flag("NL"), "flag-netherlands"),
            country("gb", "United Kingdom", "Europe/London", "en", Mark.flag("GB"), "flag-united-kingdom"),
            country("us-east", "United States (East)", "America/New_York", "en", Mark.flag("US"), "flag-united-states"),
            country(
                    "us-central",
                    "United States (Central)",
                    "America/Chicago",
                    "en",
                    Mark.flag("US"),
                    "flag-united-states"),
            country(
                    "us-mountain",
                    "United States (Mountain)",
                    "America/Denver",
                    "en",
                    Mark.flag("US"),
                    "flag-united-states"),
            country(
                    "us-west",
                    "United States (West)",
                    "America/Los_Angeles",
                    "en",
                    Mark.flag("US"),
                    "flag-united-states"),
            country("other", "Anderes Land / Other country", "", "en", Mark.WORLD, "flag-other"));

    private DefaultCountries() {}

    private static OnboardingSpec.CountrySpec country(
            final String key,
            final String name,
            final String zone,
            final String language,
            final String emoji,
            final String glyph) {
        return Specs.createUnsafe(
                OnboardingSpec.CountrySpec.class,
                Map.of("key", key, "name", name, "zone", zone, "language", language, "emoji", emoji, "glyph", glyph));
    }
}
