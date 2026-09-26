package eu.nordtal.s2.common.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.RepositoryRoot;
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
import org.junit.jupiter.api.Test;

/**
 * Checks that a call site picks a {@link Feedback} category and never names a sound itself.
 *
 * Whether the sounds are good or distinguishable needs a person with headphones.
 */
class SoundVocabularyTest {

    /** Every module that plays a sound to a Minecraft client, or could. */
    private static final List<String> MODULES = List.of("smp", "limbo", "hunger-games", "proxy");

    /** Every way of naming a sound directly, and what to do instead. */
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
    private static final Pattern BARE_SOUND_CONSTANT = Pattern.compile("(?<![A-Za-z0-9_.])Sound\\.");

    /**
     * The files that may name a sound, and why: one adapter per Paper module that plays anything.
     * An entry that is <em>not</em> an adapter is the thing this list exists to make visible.
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "smp/src/main/java/eu/nordtal/s2/smp/feedback/SmpSounds.java",
            "smp's sound adapter - the one place in the module that turns a category into a packet",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/feedback/HungerGamesSounds.java",
            "hunger-games' sound adapter - the same twenty lines, for the same reason: a shared"
                    + " adapter in :common would put org.bukkit.entity.Player in a jar that is"
                    + " shaded into a Velocity plugin",
            "proxy/src/main/java/eu/nordtal/s2/proxy/feedback/" + "ProxySounds.java",
            "proxy's sound adapter - the Velocity-side twin of the"
                    + " other two: the proxy holds the client connection itself, so it can call"
                    + " Player#playSound the same way RestartWatch already calls sendMessage and"
                    + " showTitle on a player standing on any backend");

    @Test
    void onlyTheSoundAdaptersNameASound() {
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
        assertEquals(
                List.of(),
                offenders,
                "the sound vocabulary only means anything while the categories are the only thing a"
                        + " call site can choose. If a new module genuinely needs an adapter of its"
                        + " own, add it to ALLOWED with the reason; if this is a call site, give it"
                        + " a Feedback category");
    }

    @Test
    void everyAllowlistedAdapterStillExistsAndStillPlaysSomething() {
        final List<String> gone = ALLOWED.keySet().stream()
                .filter(relative -> {
                    final Path path = RepositoryRoot.resolve(relative);
                    return !Files.isRegularFile(path) || !read(path).contains("playSound(");
                })
                .sorted()
                .toList();
        assertEquals(
                List.of(),
                gone,
                "an entry here for a file that is gone, or that plays nothing any more, is an"
                        + " exception nobody is taking any more - delete it, so the list keeps"
                        + " meaning what it says");
    }

    /** Checks that the enum has no members, so the config file stays the only source of a sound. */
    @Test
    void feedbackCarriesNothingButItsConstants() {
        assertEquals(
                12,
                Feedback.values().length,
                "ten categories, of which open/close is two constants, plus STAGING and RECLAIMED."
                        + " A THIRTEENTH IS A DECISION FOR THE OWNER - a"
                        + " vocabulary that grows to fit each new call site is not a vocabulary");
        // values/valueOf are the enum's API; $values is javac's array holder, not always marked synthetic.
        assertEquals(
                List.of(),
                Stream.of(Feedback.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .filter(name -> !name.equals("values") && !name.equals("valueOf") && !name.startsWith("$"))
                        .sorted()
                        .toList(),
                "Feedback is a name and nothing else - what a category sounds like belongs in a"
                        + " module's config.yml, parsed into FeedbackSounds");
        assertEquals(
                List.of(),
                Stream.of(Feedback.class.getDeclaredFields())
                        .filter(field -> !field.isEnumConstant() && !field.isSynthetic())
                        .map(java.lang.reflect.Field::getName)
                        .sorted()
                        .toList(),
                "a field on Feedback is a sound name waiting to be hardcoded");
    }

    private static List<Path> sources(final String module) {
        final Path root = RepositoryRoot.resolve(module + "/src/main");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot walk " + root, e);
        }
    }

    private static String read(final Path path) {
        try {
            return withoutComments(Files.readString(path, StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    /**
     * Returns the source with every comment blanked, so a comment explaining the rule does not fail it.
     *
     * Blanked rather than deleted, so no two lines are joined. A {@code //} inside a string literal can only
     * cause a false pass on the rest of that line.
     */
    private static String withoutComments(final String source) {
        final StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            final char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                while (i < source.length()
                        && !(source.charAt(i) == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                out.append("  ");
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
