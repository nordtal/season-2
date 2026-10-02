package eu.nordtal.s2.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.s2.settings.network.MotdSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The shipped MOTDs carry no glyph, only resolvable placeholders, and two lines.
 *
 * The server list draws a MOTD before any resource pack, so a private-use code point renders as a box.
 */
class MotdTextTest {

    /** The five defaults, read off a real instance as a deployment gets them. */
    private final MotdSpec motd = Specs.createDefault(MotdSpec.class);

    /** Every name {@code Placeholders#resolve} answers, plus its one prefix form. */
    private static final Set<String> RESOLVED = Set.of(
            "season",
            "online",
            "max",
            "phase",
            "countdown",
            "hg-state",
            "hg-teams",
            "hg-teams-alive",
            "hg-participants",
            "hg-alive",
            "hg-eliminated",
            "smp-milestone",
            "smp-milestone-progress",
            "smp-milestones-done",
            "smp-milestones-total",
            "smp-aura-total",
            "smp-players");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private List<String> all() {
        return List.of(motd.preLaunch(), motd.preEvent(), motd.startEvent(), motd.smp(), motd.maintenance());
    }

    @Test
    void noMotdCarriesAGlyph() {
        final List<String> offenders = new ArrayList<>();
        for (final String line : all()) {
            line.codePoints()
                    .filter(MotdTextTest::isPrivateUse)
                    .forEach(codePoint -> offenders.add(
                            "U+" + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT) + " in " + line));
        }
        assertEquals(
                List.of(),
                offenders,
                "a private-use character in a MOTD is a box in the server browser: the client draws"
                        + " that list in its own font, before it has ever been offered the pack");
    }

    @Test
    void everyPlaceholderResolves() {
        final List<String> unknown = new ArrayList<>();
        for (final String line : all()) {
            final Matcher matcher = PLACEHOLDER.matcher(line);
            while (matcher.find()) {
                final String name = matcher.group(1);
                if (!name.startsWith("players:") && !RESOLVED.contains(name)) {
                    unknown.add(name + " in " + line);
                }
            }
        }
        assertEquals(
                List.of(),
                unknown,
                "Placeholders leaves an unknown name standing rather than blanking it, so a typo"
                        + " here reaches the server list as literal braces - on the one surface"
                        + " nobody inside the network can see");
    }

    @Test
    void everyMotdHasTwoLines() {
        for (final String line : all()) {
            assertTrue(
                    line.contains("<newline>"),
                    "this MOTD is one line: " + line + ". The first line is the brand and never"
                            + " varies, so without a second one the server list says nothing about"
                            + " the phase - which is the whole reason the name stopped carrying it");
        }
    }

    /** Both private-use areas, so a character pasted from an older file is still caught. */
    private static boolean isPrivateUse(final int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }
}
