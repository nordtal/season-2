package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.common.message.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Properties of the whole command surface, which no single command can be asked about.
 *
 * The one that matters most is the description: the help output names
 * {@link Declaration#describeKey()} without checking whether it exists, because checking at the
 * point of use would mean either a silent fallback - which is how a reader ends up being told that
 * {@code /smp aura} is for {@code command.describe.smp.aura} - or a branch that only runs when
 * somebody mistypes. Asserting it here instead means a new command cannot ship without its
 * sentence, in both languages.
 */
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
    void aRootDefaultIsRunnableBare() {
        // Whatever is put in that map has to be a command the adapters can dispatch from the bare root: two segments.
        final java.util.Set<String> roots = Catalogue.all().stream()
                .map(declaration -> declaration.path().getFirst())
                .collect(java.util.stream.Collectors.toSet());
        int found = 0;
        for (final String root : roots) {
            final java.util.Optional<Declaration> preset = Catalogue.rootDefault(root, true);
            if (preset.isEmpty()) {
                continue;
            }
            found++;
            final Declaration declaration = preset.get();
            assertTrue(Catalogue.all().contains(declaration), root + ": the default is not in the catalogue");
            assertEquals(2, declaration.path().size(), root + ": " + declaration.name());
            assertEquals(root, declaration.path().getFirst(), declaration.name() + " is under another root");
            assertTrue(
                    declaration.arguments().stream().noneMatch(Argument::required),
                    declaration.name() + " needs an argument, so a bare root could not run it");
        }
        assertEquals(2, found, "exactly /phase and /update have a default today; changing that is a" + " decision");
        assertEquals(java.util.Optional.of(PhaseCommands.SHOW), Catalogue.rootDefault("phase", true));
        // /update alone is the report: it had to become /update check because Discord cannot run a root that has.
        assertEquals(
                java.util.Optional.of(eu.nordtal.s2.commands.update.UpdateCommands.REPORT),
                Catalogue.rootDefault("update", true));
    }

    @Test
    void theRootDefaultIsGated() {
        // Both adapters reach the default by calling run/dispatch on the child directly.
        for (final String root : Catalogue.all().stream()
                .map(declaration -> declaration.path().getFirst())
                .collect(java.util.stream.Collectors.toSet())) {
            Catalogue.rootDefault(root, true)
                    .filter(Declaration::adminOnly)
                    .ifPresent(declaration -> assertEquals(
                            java.util.Optional.empty(),
                            Catalogue.rootDefault(root, false),
                            "/" + root + " hands " + declaration.name() + " to a non-admin"));
        }
        assertEquals(
                java.util.Optional.empty(),
                Catalogue.rootDefault("phase", false),
                "/phase show is admin-only, so a bare /phase from a player must fall through to help");
        assertEquals(
                java.util.Optional.empty(),
                Catalogue.rootDefault("update", false),
                "/update check is admin-only; a player.s bare /update runs nothing");
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
        // discord_user.admin, mirrored from the Discord role.
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
