package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import eu.nordtal.s2.commands.phase.PhaseCommands;
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
    @DisplayName("a root's default is one of its own commands and runs with nothing typed")
    void aRootDefaultIsRunnableBare() {
        // /phase alone shows the phase (owner, 2026-09-05). Whatever else is ever put in that map
        // has to be a command the adapters can dispatch from the bare root: two segments, the first
        // of them the root, and no argument that has to be supplied.
        final java.util.Set<String> roots = Catalogue.all().stream()
                .map(declaration -> declaration.path().getFirst())
                .collect(java.util.stream.Collectors.toSet());
        int found = 0;
        for (final String root : roots) {
            final java.util.Optional<Declaration> preset = Catalogue.rootDefault(root);
            if (preset.isEmpty()) {
                continue;
            }
            found++;
            final Declaration declaration = preset.get();
            assertTrue(Catalogue.all().contains(declaration), root + ": the default is not in the catalogue");
            assertEquals(2, declaration.path().size(), root + ": " + declaration.name());
            assertEquals(root, declaration.path().getFirst(), declaration.name() + " is under another root");
            assertTrue(declaration.arguments().stream().noneMatch(Argument::required),
                    declaration.name() + " needs an argument, so a bare root could not run it");
        }
        assertEquals(1, found, "exactly /phase has a default today; changing that is a decision");
        assertEquals(java.util.Optional.of(PhaseCommands.SHOW), Catalogue.rootDefault("phase"));
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
        // permission nodes, no second list. The two exceptions are named here so that a third does
        // not arrive by accident: /smp status is the one thing a player may ask the SMP (read-only,
        // 2026-09-06), and announce is typed by nobody at all - its surface is SYSTEM, so no adapter
        // registers it and the only thing that can run it is a request row from a server.
        assertEquals(List.of("/smp status", "/announce"), Catalogue.all().stream()
                .filter(declaration -> !declaration.adminOnly())
                .map(Declaration::name)
                .toList());
    }

    @Test
    @DisplayName("a SYSTEM command is typed on no surface a person has")
    void systemIsAloneOnItsSurface() {
        // The adapters filter by GAME, DISCORD and CONSOLE; a declaration that carried SYSTEM next
        // to one of those would be registered as a real command with a name a server also sends.
        for (final Declaration declaration : Catalogue.all()) {
            if (declaration.surfaces().contains(Surface.SYSTEM)) {
                assertEquals(java.util.Set.of(Surface.SYSTEM), declaration.surfaces(), declaration.name());
            }
        }
    }
}
