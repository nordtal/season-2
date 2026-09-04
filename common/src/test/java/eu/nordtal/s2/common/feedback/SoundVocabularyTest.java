package eu.nordtal.s2.common.feedback;

import eu.nordtal.s2.common.RepositoryRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One sound vocabulary across the whole network: a call site picks a {@link Feedback} category and
 * cannot name a sound.
 *
 * <h2>Why this is a test and not a paragraph</h2>
 * "Use the same sound for similar events" is a request, and a request is what every codebase with
 * nine different chimes for the same kind of event started with. The rule only holds if breaking it
 * fails the build - which is the same argument {@code OneMessageFormatTest} makes for message
 * formatting, and this class is deliberately built the same way, down to the named allowlist.
 *
 * <p>At the time it was written there was not a single {@code playSound} call anywhere in the four
 * client-facing modules, so it started true and its whole job is to keep it that way. That is the
 * cheap moment to write a rule down: after the first exception exists, the test is an argument
 * rather than a fact.
 *
 * <h2>What it cannot see</h2>
 * Whether the sounds are any good, whether two categories are distinguishable through a wall, or
 * whether a menu that closes and immediately opens another sounds like one thing or two. None of
 * that exists in a JVM with no server in it; it needs a person with headphones on and is in the
 * owner's checklist outside this repository.
 */
class SoundVocabularyTest {

    /** Every module that plays a sound to a Minecraft client, or could. */
    private static final List<String> MODULES =
            List.of("smp", "limbo", "hunger-games", "network-control");

    /**
     * Every way of naming a sound directly, and what to do instead.
     *
     * <p>Substrings and one small regex rather than a parser, for the same reason
     * {@code OneMessageFormatTest} greps for {@code Component.text(}: the check has to be readable
     * by whoever it fails on, and every one of these shapes is unambiguous in Java source.
     */
    private static final Map<String, String> FORBIDDEN = new LinkedHashMap<>(Map.of(
            "playSound(",
            "a call site that plays its own sound. Take a Feedback category through the module's"
                    + " sound adapter instead - that is the whole point of the category existing",
            "org.bukkit.Sound",
            "Bukkit's Sound (or SoundCategory) reached a call site. The sound key comes from"
                    + " config.yml as a namespaced string; the platform type belongs to the adapter"
                    + " and nowhere else",
            "net.kyori.adventure.sound.",
            "Adventure's sound API reached a call site. Same rule as Bukkit's: the adapter owns the"
                    + " platform, a call site owns a category"));

    /** A bare {@code Sound.SOMETHING} constant, without matching {@code FeedbackSound.} and friends. */
    private static final Pattern BARE_SOUND_CONSTANT =
            Pattern.compile("(?<![A-Za-z0-9_.])Sound\\.");

    /**
     * The files that may name a sound, and why.
     *
     * <p>Two entries, which is what the design predicted: one adapter per Paper module that plays
     * anything. {@code limbo} has none and is not expected to get one - a waiting room whose entire
     * interface is one title has no event to chime at, and the module was reviewed for call sites on
     * 2026-09-04 and found to have zero. A third would only appear if Velocity turns out to be able
     * to play a sound at all, which needs a real client to answer (docs/presentation.md section 4).
     * Each of those is a line added here on purpose, by somebody who has read this paragraph.
     *
     * <p>An entry that is <em>not</em> an adapter is the thing this list exists to make visible.
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "smp/src/main/java/eu/nordtal/s2/smp/feedback/SmpSounds.java",
            "smp's sound adapter - the one place in the module that turns a category into a packet",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/feedback/HungerGamesSounds.java",
            "hunger-games' sound adapter - the same twenty lines, for the same reason: a shared"
                    + " adapter in :common would put org.bukkit.entity.Player in a jar that is"
                    + " shaded into a Velocity plugin");

    @Test
    @DisplayName("only the sound adapters name a sound")
    void onlyTheAdaptersNameASound() {
        final List<String> offenders = new ArrayList<>();
        for (final String module : MODULES) {
            for (final Path source : sources(module)) {
                final String relative = RepositoryRoot.relative(source);
                if (ALLOWED.containsKey(relative)) {
                    continue;
                }
                final String text = read(source);
                FORBIDDEN.forEach((marker, why) -> {
                    if (text.contains(marker)) {
                        offenders.add(relative + " contains '" + marker + "': " + why);
                    }
                });
                if (BARE_SOUND_CONSTANT.matcher(text).find()) {
                    offenders.add(relative + " names a Sound constant. Enum names move between"
                            + " Minecraft versions and registry keys do not - the key belongs in"
                            + " config.yml");
                }
            }
        }
        assertEquals(List.of(), offenders,
                "the sound vocabulary only means anything while the categories are the only thing a"
                        + " call site can choose. If a new module genuinely needs an adapter of its"
                        + " own, add it to ALLOWED with the reason; if this is a call site, give it"
                        + " a Feedback category");
    }

    @Test
    @DisplayName("every allowlisted adapter still exists and still plays something")
    void theAllowlistHasNoGhosts() {
        final List<String> gone = ALLOWED.keySet().stream()
                .filter(relative -> {
                    final Path path = RepositoryRoot.resolve(relative);
                    return !Files.isRegularFile(path) || !read(path).contains("playSound(");
                })
                .sorted()
                .toList();
        assertEquals(List.of(), gone,
                "an entry here for a file that is gone, or that no longer plays anything, is an"
                        + " exception nobody is taking any more - delete it, so the list keeps"
                        + " meaning what it says");
    }

    /**
     * The enum stays what it is: ten constants, no members.
     *
     * <p>A method, a field or a constructor argument on {@link Feedback} would be the first step
     * back towards a call site being able to say what it wants to hear - the category would start
     * carrying a default sound, and the config file would stop being the only answer.
     */
    @Test
    @DisplayName("Feedback carries nothing but its constants")
    void theEnumCarriesNothingButConstants() {
        assertEquals(10, Feedback.values().length,
                "nine categories, of which open/close is two constants. Adding an eleventh is a"
                        + " decision for the owner: a vocabulary that grows to fit each new call"
                        + " site is not a vocabulary");
        // values/valueOf are the enum's own API; $values is javac's array holder, which it does not
        // always flag as synthetic.
        assertEquals(List.of(), Stream.of(Feedback.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .filter(name -> !name.equals("values") && !name.equals("valueOf")
                                && !name.startsWith("$"))
                        .sorted()
                        .toList(),
                "Feedback is a name and nothing else - what a category sounds like belongs in a"
                        + " module's config.yml, parsed into FeedbackSounds");
        assertEquals(List.of(), Stream.of(Feedback.class.getDeclaredFields())
                        .filter(field -> !field.isEnumConstant() && !field.isSynthetic())
                        .map(java.lang.reflect.Field::getName)
                        .sorted()
                        .toList(),
                "a field on Feedback is a sound name waiting to be hardcoded");
    }

    // --- helpers ---------------------------------------------------------------------------

    private static List<Path> sources(final String module) {
        final Path root = RepositoryRoot.resolve(module + "/src/main");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot walk " + root, e);
        }
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
