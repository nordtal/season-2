package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The regions the {@code onboarding} group defaults to, for a community mostly in Europe and America.
 *
 * {@code createUnsafe} applies no defaults, so every {@code @Key} of {@link OnboardingSpec.RegionSpec} is listed.
 */
final class DefaultRegions {

    /** The network's own zone first, since most members live in it. */
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
