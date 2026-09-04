package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import eu.nordtal.s2.common.message.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the whole command surface, which no single command can be asked about.
 *
 * <h2>The one that matters most is the description</h2>
 * The help output names {@link Declaration#describeKey()} without checking whether it exists,
 * because checking at the point of use would mean either a silent fallback - which is how a reader
 * ends up being told that {@code /smp aura} is for {@code command.describe.smp.aura} - or a branch
 * that only runs when somebody mistypes. Asserting it here instead means a new command cannot ship
 * without its sentence, in both languages.
 */
class CatalogueTest {

    private static final Messages MESSAGES = Messages.load(CatalogueTest.class.getClassLoader(),
            "messages/commands", Locale.ENGLISH, Locale.GERMAN);

    @Test
    @DisplayName("every command explains itself, in both languages")
    void everyCommandHasADescription() {
        final List<String> missing = new ArrayList<>();
        for (final Declaration declaration : Catalogue.all()) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                if (!MESSAGES.hasTranslation(locale, declaration.describeKey())) {
                    missing.add(declaration.describeKey() + " (" + locale.getLanguage() + ")");
                }
            }
        }
        assertEquals(List.of(), missing,
                "a command has no sentence saying what it is for, so the help output would print"
                        + " its message key at somebody who has just mistyped it");
    }

    @Test
    @DisplayName("a usage line names every argument, in order, and says which are optional")
    void usageIsDerivedFromTheDeclaration() {
        // Derived rather than written by hand, so it cannot end up telling people to type something
        // that no longer parses - which is the way a hand-kept usage line always fails.
        for (final Declaration declaration : Catalogue.all()) {
            final String usage = declaration.usage();
            assertTrue(usage.startsWith(declaration.name()), usage);
            for (final Argument argument : declaration.arguments()) {
                assertTrue(usage.contains(argument.required()
                                ? "<" + argument.name() + ">" : "[" + argument.name() + "]"),
                        declaration.name() + "'s usage line does not name '" + argument.name()
                                + "': " + usage);
            }
        }
    }

    @Test
    @DisplayName("no two commands share a path")
    void everyPathIsUnique() {
        final Map<String, Long> byName = Catalogue.all().stream()
                .collect(Collectors.groupingBy(Declaration::name, Collectors.counting()));
        assertEquals(List.of(), byName.entrySet().stream()
                        .filter(entry -> entry.getValue() > 1)
                        .map(Map.Entry::getKey)
                        .toList(),
                "two declarations claim one command. Brigadier takes the last one silently and JDA"
                        + " refuses the whole command set.");
    }

    @Test
    @DisplayName("no command is a prefix of another, because Brigadier cannot express both")
    void noCommandIsAPrefixOfAnother() {
        // /smp objective complete <key> and a hypothetical /smp objective <key> would need the same
        // node to be both a literal and an argument. Brigadier would build it; which one wins
        // depends on registration order.
        final List<String> clashes = new ArrayList<>();
        for (final Declaration one : Catalogue.all()) {
            for (final Declaration other : Catalogue.all()) {
                if (one == other || one.arguments().isEmpty()) {
                    continue;
                }
                if (other.path().size() > one.path().size()
                        && other.path().subList(0, one.path().size()).equals(one.path())) {
                    clashes.add(one.name() + " is a prefix of " + other.name()
                            + " and takes arguments");
                }
            }
        }
        assertEquals(List.of(), clashes);
    }

    @Test
    @DisplayName("every command is admin-only, which is the whole of the authorisation model")
    void thereIsOneAdminList() {
        // discord_user.admin, mirrored from the Discord role, and the console. No LuckPerms, no
        // permission nodes, no second list. A command that is not admin-only would be the first
        // exception and should not arrive by accident.
        assertEquals(List.of(), Catalogue.all().stream()
                .filter(declaration -> !declaration.adminOnly())
                .map(Declaration::name)
                .toList());
    }
}
