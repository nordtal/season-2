package eu.nordtal.s2.networkcontrol.config;

import eu.nordtal.jcore.config.spec.Specs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the five shipped MOTDs may and may not contain.
 *
 * <h2>Why a MOTD needs a test at all</h2>
 * Because it is the one surface with no feedback loop. Nobody on the network sees it - a MOTD is
 * read by people who have not joined yet, in a list, once, and a mistake in it is invisible from
 * inside the server for as long as it lasts. Two of the three rules below have already been broken
 * in this file: the brand was coloured five different ways (see {@link BrandColourTest}), and a
 * placeholder that resolves to nothing is left standing on purpose so that it is visible - to
 * whoever is looking at the list, which is nobody we can ask.
 *
 * <h2>The three rules</h2>
 * <ol>
 *   <li><b>No glyph.</b> The server browser draws the MOTD in the client's own font, before any
 *       resource pack has been offered, let alone applied - so a private-use code point renders as
 *       a box. This is the same check {@code TabListTest} makes on the tab list frame and for the
 *       opposite reason: there the glyph has to be a parameter, here it may not exist at all.</li>
 *   <li><b>Every placeholder is one the build resolves.</b> {@code Placeholders} leaves an unknown
 *       name standing rather than blanking it, deliberately, so a typo reaches the server list as
 *       the literal text {@code {smp-milstone}}.</li>
 *   <li><b>Two lines.</b> Every client draws exactly two, and the first is the brand, so a MOTD
 *       with no {@code <newline>} says nothing about the phase at all.</li>
 * </ol>
 */
class MotdTextTest {

    /**
     * The five defaults, built the same way {@code NetworkSpec#motd()} builds them.
     *
     * <p>Read off a real instance rather than off the source: what a deployment gets is whatever
     * {@code Specs.createDefault} returns, and a default body that is never called is a default
     * that does not exist.</p>
     */
    private final NetworkSpec.MotdSpec motd = Specs.createDefault(NetworkSpec.MotdSpec.class);

    /** Every name {@code Placeholders#resolve} answers, plus its one prefix form. */
    private static final Set<String> RESOLVED = Set.of(
            "online", "max", "phase", "countdown",
            "hg-state", "hg-teams", "hg-teams-alive", "hg-participants", "hg-alive",
            "hg-eliminated",
            "smp-milestone", "smp-milestone-progress", "smp-milestones-done",
            "smp-milestones-total", "smp-aura-total", "smp-players");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private List<String> all() {
        return List.of(motd.preLaunch(), motd.preEvent(), motd.startEvent(), motd.smp(),
                motd.maintenance());
    }

    @Test
    @DisplayName("no MOTD carries a glyph, because the list is drawn before any pack exists")
    void noMotdCarriesAGlyph() {
        final List<String> offenders = new ArrayList<>();
        for (final String line : all()) {
            line.codePoints().filter(MotdTextTest::isPrivateUse)
                    .forEach(codePoint -> offenders.add(
                            "U+" + Integer.toHexString(codePoint).toUpperCase() + " in " + line));
        }
        assertEquals(List.of(), offenders,
                "a private-use character in a MOTD is a box in the server browser: the client draws"
                        + " that list in its own font, before it has ever been offered the pack");
    }

    @Test
    @DisplayName("every placeholder is one this build actually resolves")
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
        assertEquals(List.of(), unknown,
                "Placeholders leaves an unknown name standing rather than blanking it, so a typo"
                        + " here reaches the server list as literal braces - on the one surface"
                        + " nobody inside the network can see");
    }

    @Test
    @DisplayName("every MOTD has a second line, which is the one that says what phase this is")
    void everyMotdHasTwoLines() {
        for (final String line : all()) {
            assertTrue(line.contains("<newline>"),
                    "this MOTD is one line: " + line + ". The first line is the brand and never"
                            + " varies, so without a second one the server list says nothing about"
                            + " the phase - which is the whole reason the name stopped carrying it");
        }
    }

    /**
     * Both private-use areas, not just the one this pack uses - the same rule {@code TabListTest}
     * states: checking only the current range quietly stops catching a character pasted out of an
     * older file.
     */
    private static boolean isPrivateUse(final int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }
}
