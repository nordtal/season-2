package eu.nordtal.s2.common.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The matching rule the whole command gate rests on.
 *
 * <p>Every one of these cases is a way past a filter that only compared strings, and none of them is
 * hypothetical: a client can send {@code /minecraft:me}, Bukkit resolves {@code /Me}, and a player
 * types {@code /msg} with arguments after it. The rule is small enough to hold in one class, which
 * is the point of keeping it out of the two adapters.</p>
 */
class CommandAllowlistTest {

    private static final CommandAllowlist LIST =
            CommandAllowlist.parse(List.of("smp status", "msg", "hg ready"));

    @Test
    @DisplayName("everything under an allowed path is allowed")
    void argumentsDoNotChangeTheAnswer() {
        assertTrue(LIST.allows("/msg"));
        assertTrue(LIST.allows("/msg Someone hello there"));
        assertTrue(LIST.allows("msg Someone hello"), "the leading slash is optional");
        assertTrue(LIST.allows("/smp status"));
        assertTrue(LIST.allows("/hg ready"));
    }

    @Test
    @DisplayName("a path above an allowed one is allowed, or its own help could not be reached")
    void aPrefixOfAnEntryIsAllowed() {
        // /smp on its own prints what can be typed under it. Refusing it would mean the one thing a
        // player may ask the SMP is a command they can only find out about by being told.
        assertTrue(LIST.allows("/smp"));
        assertTrue(LIST.allows("/hg"));
    }

    @Test
    @DisplayName("a sibling under an allowed root is not allowed")
    void aDifferentSubcommandIsRefused() {
        assertFalse(LIST.allows("/smp reload"));
        assertFalse(LIST.allows("/hg start"));
    }

    @Test
    @DisplayName("nothing outside the list is allowed, which is the point of a list of what is")
    void anythingElseIsRefused() {
        assertFalse(LIST.allows("/server hunger-games"),
                "this is the command the whole allowlist exists for");
        assertFalse(LIST.allows("/me waves"));
        assertFalse(LIST.allows("/help"));
        assertFalse(LIST.allows("/tell Someone hello"));
    }

    @Test
    @DisplayName("case and a namespace are normalised away on both sides")
    void theTwoWaysAClientCanSpellACommand() {
        // Bukkit resolves /Me and /minecraft:me to the same command. A filter that compared raw
        // text would refuse the plain form and wave both of these through, which is the failure
        // that looks exactly like working.
        assertTrue(LIST.allows("/MSG Someone hi"));
        assertTrue(LIST.allows("/nordtal:msg Someone hi"));
        assertFalse(LIST.allows("/Minecraft:Me waves"));
        assertTrue(CommandAllowlist.parse(List.of("/MSG ")).allows("/msg someone"));
    }

    @Test
    @DisplayName("an argument containing a colon is not a namespace")
    void onlyTheFirstSegmentLosesANamespace() {
        assertTrue(LIST.allows("/msg Someone http://example.invalid"));
    }

    @Test
    @DisplayName("a line that is not a command at all is left to the platform")
    void anEmptyLineIsNotRefused() {
        // "/" and a line of spaces. Answering "that command does not exist" to an empty string is
        // worse than letting the platform say whatever it says about it.
        assertTrue(LIST.allows("/"));
        assertTrue(LIST.allows("   "));
        assertTrue(LIST.allows(null));
    }

    @Test
    @DisplayName("an empty list allows nothing, and that is expressible on purpose")
    void nothingIsAValidList() {
        assertFalse(CommandAllowlist.NOTHING.allows("/msg"));
        assertFalse(CommandAllowlist.NOTHING.allowsRoot("msg"));
        assertTrue(CommandAllowlist.NOTHING.allows("/"), "still not a command");
    }

    @Test
    @DisplayName("a root is visible when anything under it is allowed")
    void rootsAreWhatTheCompletionFiltersAskAbout() {
        // Both completion filters work on root labels: Paper's PlayerCommandSendEvent hands over
        // whole commands, and the tree Velocity sends has one child per root. Hiding a subcommand
        // is not this list's job - Brigadier's own requires does that.
        assertTrue(LIST.allowsRoot("smp"));
        assertTrue(LIST.allowsRoot("minecraft:smp"));
        assertTrue(LIST.allowsRoot("HG"));
        assertFalse(LIST.allowsRoot("server"));
        assertFalse(LIST.allowsRoot(""));
    }

    @Test
    @DisplayName("a blank line is skipped rather than becoming an entry that allows everything")
    void blanksAreDropped() {
        final CommandAllowlist parsed = CommandAllowlist.parse(List.of("msg", "", "  ", "/"));
        assertEquals(1, parsed.entries().size());
        assertFalse(parsed.allows("/server smp"),
                "a blank entry read as 'no segments' would match every command there is");
    }

    @Test
    @DisplayName("a list survives the round trip through the row the proxy publishes it in")
    void serialisationIsAnInverse() {
        final CommandAllowlist parsed = CommandAllowlist.parse(
                List.of("/smp status", "msg", "hg ready", "discord"));
        assertEquals(parsed, CommandAllowlist.deserialise(parsed.serialise()));
        assertEquals("smp status\nmsg\nhg ready\ndiscord", parsed.serialise());
        // A row that was never written and a list that was emptied on purpose are told apart by the
        // caller - AllowlistDirectory answers Optional.empty for the first - so both of these
        // legitimately mean "the list allows nothing".
        assertEquals(CommandAllowlist.NOTHING, CommandAllowlist.deserialise(null));
        assertEquals(CommandAllowlist.NOTHING, CommandAllowlist.deserialise(""));
        assertEquals(CommandAllowlist.NOTHING,
                CommandAllowlist.deserialise(CommandAllowlist.NOTHING.serialise()));
    }

    @Test
    @DisplayName("an entry with no segments is refused rather than silently allowing everything")
    void theConstructorRefusesTheOneValueItCannotMean() {
        assertThrows(IllegalArgumentException.class,
                () -> new CommandAllowlist(List.of(List.of())));
    }
}
