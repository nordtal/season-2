package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.messages.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Properties of the whole command surface, which no single command can be asked about. */
class CatalogueTest {

    private static final Messages MESSAGES =
            Messages.load(CatalogueTest.class.getClassLoader(), "messages/commands", Locale.ENGLISH, Locale.GERMAN);

    @Test
    void everyCommandHasADescription() {
        final List<String> missing = new ArrayList<>();
        for (final Declaration declaration : Catalogue.all()) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                if (!MESSAGES.hasTranslation(locale, declaration.describeKey())) {
                    missing.add(declaration.describeKey() + " (" + locale.getLanguage() + ")");
                }
            }
        }
        assertEquals(
                List.of(),
                missing,
                "a command has no sentence saying what it is for, so the help output would print"
                        + " its message key at somebody who has just mistyped it");
    }

    @Test
    void usageIsDerivedFromTheDeclaration() {
        // Derived rather than written by hand, so it cannot tell people to type something that no longer parses.
        for (final Declaration declaration : Catalogue.all()) {
            final String usage = declaration.usage();
            assertTrue(usage.startsWith(declaration.name()), usage);
            for (final Argument argument : declaration.arguments()) {
                assertTrue(
                        usage.contains(argument.required() ? "<" + argument.name() + ">" : "[" + argument.name() + "]"),
                        declaration.name() + "'s usage line does not name '" + argument.name() + "': " + usage);
            }
        }
    }

    @Test
    void everyPathIsUnique() {
        final Map<String, Long> byName =
                Catalogue.all().stream().collect(Collectors.groupingBy(Declaration::name, Collectors.counting()));
        assertEquals(
                List.of(),
                byName.entrySet().stream()
                        .filter(entry -> entry.getValue() > 1)
                        .map(Map.Entry::getKey)
                        .toList(),
                "two declarations claim one command. Brigadier takes the last one silently and JDA"
                        + " refuses the whole command set.");
    }

    @Test
    void noCommandIsAPrefixOfAnother() {
        // /smp objective complete <key> and a hypothetical /smp objective <key> would need one node to be both.
        final List<String> clashes = new ArrayList<>();
        for (final Declaration one : Catalogue.all()) {
            for (final Declaration other : Catalogue.all()) {
                if (one.equals(other) || one.arguments().isEmpty()) {
                    continue;
                }
                if (other.path().size() > one.path().size()
                        && other.path().subList(0, one.path().size()).equals(one.path())) {
                    clashes.add(one.name() + " is a prefix of " + other.name() + " and takes arguments");
                }
            }
        }
        assertEquals(List.of(), clashes);
    }

    @Test
    void thereIsOneAdminList() {
        assertEquals(
                List.of(),
                Catalogue.all().stream()
                        .filter(declaration -> !declaration.adminOnly())
                        .map(Declaration::name)
                        .toList());
    }

    @Test
    void systemIsAloneOnItsSurface() {
        // The adapters filter by GAME, DISCORD and CONSOLE.
        final java.util.Set<Surface> registered = java.util.Set.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE);
        for (final Declaration declaration : Catalogue.all()) {
            if (declaration.surfaces().contains(Surface.SYSTEM)) {
                assertEquals(
                        java.util.Set.of(),
                        declaration.surfaces().stream()
                                .filter(registered::contains)
                                .collect(java.util.stream.Collectors.toSet()),
                        declaration.name() + " is a SYSTEM command and is also on a surface some"
                                + " adapter builds a command tree from, which would register a real"
                                + " command with a name a server also sends.");
            }
        }
    }
}
