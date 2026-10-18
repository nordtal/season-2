package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The default regions of the {@code onboarding} group.
 * {@code createUnsafe} applies no defaults, so {@code region} lists every {@code @Key} of
 * {@link OnboardingSpec.RegionSpec}.
 */
final class DefaultRegions {

    static final List<OnboardingSpec.RegionSpec> LIST = List.of(
            region("Central Europe", "Europe/Berlin"),
            region("United Kingdom", "Europe/London"),
            region("Eastern Europe", "Europe/Helsinki"),
            region("US East", "America/New_York"),
            region("US Central", "America/Chicago"),
            region("US Mountain", "America/Denver"),
            region("US West", "America/Los_Angeles"),
            region("South America", "America/Sao_Paulo"),
            region("India", "Asia/Kolkata"),
            region("China", "Asia/Shanghai"),
            region("Japan and Korea", "Asia/Tokyo"),
            region("Australia East", "Australia/Sydney"));

    private DefaultRegions() {}

    private static OnboardingSpec.RegionSpec region(final String name, final String zone) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("zone", zone);
        return Specs.createUnsafe(OnboardingSpec.RegionSpec.class, values);
    }
}
