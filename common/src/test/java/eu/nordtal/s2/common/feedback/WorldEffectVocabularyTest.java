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
 * The same rule {@link SoundVocabularyTest} makes about sound, made about what a moment looks like:
 * one file per module names a particle or launches a firework, and no other file may.
 *
 * <h2>Why this is a test and why it exists now rather than later</h2>
 * When it was written there was exactly one such file in the whole repository - {@code smp}'s
 * {@code WorldEffects} - and four call sites behind it. That is the cheap moment to write the rule
 * down, for the reason the sound test states about itself: once the second file exists, a rule like
 * this is an argument with somebody rather than a fact about the code.
 *
 * <p>The failure it prevents is not a crash. It is a balloon that puffs cloud in one place and
 * smoke in another, and a grave that looks like a nether portal because the third call site picked
 * whatever was in the autocomplete. Nothing about that fails a build, nothing logs it, and by the
 * time somebody notices, changing it means finding every site again.
 *
 * <h2>What it deliberately does not do</h2>
 * There is no {@code effects.yml}, and that is a decision rather than an omission. Ten sound
 * categories cover some forty call sites, so a config file compresses; four effects cover four call
 * sites, so a config file would be four entries pointing one-to-one at four methods. The reasoning
 * is in {@code WorldEffects}' own javadoc and in {@code docs/presentation.md} section 6, and a fifth
 * and sixth moment appearing is the point at which to reopen it out loud.
 *
 * <p>It also cannot say whether any of it looks any good, whether forty players' worth of rockets
 * is a lag spike, or whether the celebration is visible from inside a cave. That needs a server and
 * is on the owner's checklist outside this repository.
 */
class WorldEffectVocabularyTest {

    /** Every module that can draw something in a world. */
    private static final List<String> MODULES =
            List.of("smp", "limbo", "hunger-games", "network-control");

    /** Every way of naming an effect directly, and what to do instead. */
    private static final Map<String, String> FORBIDDEN = new LinkedHashMap<>(Map.of(
            "spawnParticle(",
            "a call site drawing its own particles. Give the moment a method on the module's effect"
                    + " adapter instead, so the same kind of moment keeps looking the same",
            "org.bukkit.Particle",
            "Bukkit's Particle reached a call site. The adapter owns the platform types; a call site"
                    + " owns a moment",
            "org.bukkit.entity.Firework",
            "a firework outside the adapter. A rocket carrying explosion effects damages whatever is"
                    + " near it when it bursts, whoever launched it - the adapter stamps its own and"
                    + " refuses their damage, and a rocket spawned elsewhere is not stamped"));

    /** A bare {@code Particle.SOMETHING} constant, without matching a qualified name or a field. */
    private static final Pattern BARE_PARTICLE_CONSTANT =
            Pattern.compile("(?<![A-Za-z0-9_.])Particle\\.");

    /**
     * The files that may name an effect, and why.
     *
     * <p>One entry, and a second would be a Paper module that grew a world moment of its own -
     * {@code hunger-games} is the obvious candidate, for the border closing and the ceremony. It
     * would be a file of about this size for the same reason its sound adapter is:
     * {@code org.bukkit} may not appear in {@code :common}, which is shaded into a Velocity plugin.
     *
     * <p>An entry that is <em>not</em> an adapter is what this list exists to make visible.
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "smp/src/main/java/eu/nordtal/s2/smp/feedback/WorldEffects.java",
            "smp's effect adapter - the one place in the module that draws anything in a world");

    @Test
    @DisplayName("only the effect adapters name a particle or a firework")
    void onlyTheAdaptersNameAnEffect() {
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
                if (BARE_PARTICLE_CONSTANT.matcher(text).find()) {
                    offenders.add(relative + " names a Particle constant. Enum names move between"
                            + " Minecraft versions, and a moment that picks its own look is how two"
                            + " balloons end up puffing different things");
                }
            }
        }
        assertEquals(List.of(), offenders,
                "if a module genuinely needs an adapter of its own, add it to ALLOWED with the"
                        + " reason; if this is a call site, give the moment a method on the adapter");
    }

    @Test
    @DisplayName("every allowlisted adapter still exists and still draws something")
    void theAllowlistHasNoGhosts() {
        final List<String> gone = ALLOWED.keySet().stream()
                .filter(relative -> {
                    final Path path = RepositoryRoot.resolve(relative);
                    return !Files.isRegularFile(path) || !read(path).contains("spawnParticle(");
                })
                .sorted()
                .toList();
        assertEquals(List.of(), gone, "an entry here for a file that draws nothing any more is an"
                + " exception nobody is taking - delete it, so the list keeps meaning what it says");
    }

    /**
     * Every rocket the adapter launches is stamped, and the stamp is what {@code onDamage} reads.
     *
     * <p>Grepping for two strings is a poor substitute for launching one and standing next to it,
     * and it is what can be checked without a server. What it catches is the shape of the mistake
     * that matters: a second {@code spawn(..., Firework.class, ...)} added later, next to the first,
     * without the line that stamps it - after which the celebration takes four hearts off the person
     * it is celebrating.
     */
    @Test
    @DisplayName("a launched firework is stamped, and the stamp is refused damage")
    void everyRocketIsDisarmed() {
        final String adapter = read(RepositoryRoot.resolve(
                "smp/src/main/java/eu/nordtal/s2/smp/feedback/WorldEffects.java"));

        assertEquals(count(adapter, "Firework.class"), count(adapter, "PersistentDataType.BYTE, (byte) 1"),
                "every place that spawns a rocket has to stamp it in the same method - an unstamped"
                        + " one is a rocket onDamage will not recognise");
        assertEquals(true, adapter.contains("event.setCancelled(true)"),
                "the adapter has to refuse damage from its own rockets; without that handler the"
                        + " stamp is decoration");
    }

    private static int count(final String text, final String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
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
