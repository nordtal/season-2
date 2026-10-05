package eu.nordtal.season.settings.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins the matching rule the command gate rests on; each case is a real way past a string comparison. */
class CommandAllowlistTest {

    private static final CommandAllowlist LIST = CommandAllowlist.parse(List.of("smp status", "msg", "hg ready"));

    @Test
    void everythingUnderAnAllowedPathIsAllowed() {
        assertTrue(LIST.allows("/msg"));
        assertTrue(LIST.allows("/msg Someone hello there"));
        assertTrue(LIST.allows("msg Someone hello"), "the leading slash is optional");
        assertTrue(LIST.allows("/smp status"));
        assertTrue(LIST.allows("/hg ready"));
    }

    @Test
    void aPathAboveAnAllowedOneIsAllowedOrItsOwnHelpCouldNotBeReached() {
        // /smp on its own lists what can be typed under it.
        assertTrue(LIST.allows("/smp"));
        assertTrue(LIST.allows("/hg"));
    }

    @Test
    void aSiblingUnderAnAllowedRootIsNotAllowed() {
        assertFalse(LIST.allows("/smp reload"));
        assertFalse(LIST.allows("/hg start"));
    }

    @Test
    void nothingOutsideTheListIsAllowedWhichIsThePointOfAListOfWhatIs() {
        assertFalse(LIST.allows("/server hunger-games"), "this is the command the whole allowlist exists for");
        assertFalse(LIST.allows("/me waves"));
        assertFalse(LIST.allows("/help"));
        assertFalse(LIST.allows("/tell Someone hello"));
    }

    @Test
    void caseAndANamespaceAreNormalisedAwayOnBothSides() {
        // Bukkit resolves /Me and /minecraft:me to the same command, so a raw-text filter would be wrong.
        assertTrue(LIST.allows("/MSG Someone hi"));
        assertTrue(LIST.allows("/nordtal:msg Someone hi"));
        assertFalse(LIST.allows("/Minecraft:Me waves"));
        assertTrue(CommandAllowlist.parse(List.of("/MSG ")).allows("/msg someone"));
    }

    @Test
    void anArgumentContainingAColonIsNotANamespace() {
        assertTrue(LIST.allows("/msg Someone http://example.invalid"));
    }

    @Test
    void aLineThatIsNotACommandAtAllIsLeftToThePlatform() {
        // An empty command is left to the platform.
        assertTrue(LIST.allows("/"));
        assertTrue(LIST.allows("   "));
        assertTrue(LIST.allows(null));
    }

    @Test
    void anEmptyListAllowsNothingAndThatIsExpressibleOnPurpose() {
        assertFalse(CommandAllowlist.NOTHING.allows("/msg"));
        assertFalse(CommandAllowlist.NOTHING.allowsRoot("msg"));
        assertTrue(CommandAllowlist.NOTHING.allows("/"), "still not a command");
    }

    @Test
    void aRootIsVisibleWhenAnythingUnderItIsAllowed() {
        // Completion filters work on root labels; hiding a subcommand is Brigadier's requires.
        assertTrue(LIST.allowsRoot("smp"));
        assertTrue(LIST.allowsRoot("minecraft:smp"));
        assertTrue(LIST.allowsRoot("HG"));
        assertFalse(LIST.allowsRoot("server"));
        assertFalse(LIST.allowsRoot(""));
    }

    @Test
    void aBlankLineIsSkippedRatherThanBecomingAnEntryThatAllowsEverything() {
        final CommandAllowlist parsed = CommandAllowlist.parse(List.of("msg", "", "  ", "/"));
        assertEquals(1, parsed.entries().size());
        assertFalse(
                parsed.allows("/server smp"), "a blank entry read as 'no segments' would match every command there is");
    }

    @Test
    void anEntryWithNoSegmentsIsRefusedRatherThanSilentlyAllowingEverything() {
        assertThrows(IllegalArgumentException.class, () -> new CommandAllowlist(List.of(List.of())));
    }
}
