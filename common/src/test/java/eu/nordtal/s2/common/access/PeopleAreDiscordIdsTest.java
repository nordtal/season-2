package eu.nordtal.s2.common.access;

import eu.nordtal.s2.common.RepositoryRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A person in this schema is a discord id, and a Minecraft UUID is never spelled as a string.
 *
 * <h2>The failure it exists for</h2>
 * Every column that names a person - {@code smp_grave.owner_id}, {@code smp_grave.looted_by},
 * {@code smp_aura_event.discord_id}, {@code smp_contribution.discord_id} - is
 * {@code varchar(32)} and holds a discord id. A Minecraft UUID printed with
 * {@code getUniqueId().toString()} is 36 characters and does not fit, and PostgreSQL says so at the
 * moment of the write rather than at the moment of the mistake.
 *
 * <p>{@code Graves#onClosed} did exactly that. {@code markGraveLooted} threw
 * {@code value too long for type character varying(32)} on <em>every</em> loot, from inside the
 * async task that also erases the grave and refunds the experience - so no grave was ever marked
 * looted, every grave was restored on every server start, and nobody ever got their levels back.
 * Nothing in the game showed it: the window opens, the items come out, the window closes, which is
 * the whole of what a player can see. It was found by reading the table (finding 132).
 *
 * <h2>Why the rule is absolute</h2>
 * There were <b>zero</b> call sites left the day it was written, which is the cheap moment for a
 * rule like this - after the first exception exists it becomes an argument instead of a fact. Where
 * a UUID genuinely belongs in the database it is a {@code uuid} column ({@code account_link.mc_uuid},
 * {@code command_request.mc_uuid}) and is bound as a {@link java.util.UUID}, not as text. If a
 * future line really needs the string form, that is a decision to take out loud rather than a
 * search to weaken.
 */
class PeopleAreDiscordIdsTest {

    /** Every module that talks to a player and to the database. */
    private static final List<String> SOURCE_ROOTS = List.of(
            "common/src/main/java",
            "paper-common/src/main/java",
            "smp/src/main/java",
            "limbo/src/main/java",
            "hunger-games/src/main/java",
            "network-control/src/main/java");

    private static final String FORBIDDEN = "getUniqueId().toString()";

    @Test
    @DisplayName("no Minecraft UUID is turned into a string on its way to the database")
    void noSourceSpellsAUuid() {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : sources()) {
            final List<String> lines = read(source);
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                final String trimmed = line.strip();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (line.contains(FORBIDDEN)) {
                    offenders.add(RepositoryRoot.relative(source) + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), offenders,
                "a person in this schema is a discord id in a varchar(32) column, and a Minecraft"
                        + " UUID printed as text is 36 characters - resolve the discord id"
                        + " (Identities#discordIdOf) or bind a real java.util.UUID to a uuid column");
    }

    private static List<Path> sources() {
        final List<Path> found = new ArrayList<>();
        for (final String root : SOURCE_ROOTS) {
            final Path directory = RepositoryRoot.resolve(root);
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException(root + " is not a directory - the roots have moved");
            }
            try (Stream<Path> tree = Files.walk(directory)) {
                tree.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(found::add);
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot walk " + root, e);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no sources found - the roots have moved");
        }
        return found;
    }

    private static List<String> read(final Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + source, e);
        }
    }
}
