package eu.nordtal.s2.smp.grave;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The head is not loot: an emptied grave hands it over on close, by itself.
 *
 * <b>What this guarantees</b>
 *
 * With every item taken out, closing the window puts the head into the player's inventory - dropped if it does not
 * fit - and the grave goes. The head itself is not something anybody picks up; pressing "take everything" a second
 * time just to collect it is exactly the step that does not happen.
 *
 * <b>Why a text search, again</b>
 *
 * The same reason {@link OneGraveOneWindowTest} gives: a grave being emptied is a server, a client and an inventory
 * of slots, and none of the three exists in a unit test here. What can be held is the shape - which method reads the
 * head slot, and what the emptiness decision is made of.
 */
class GraveHeadReturnsOnCloseTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/grave/Graves.java";

    @Test
    void takeAllLeavesTheHeadAlone() {
        final String takeAll = body("private void takeAll(");

        assertFalse(
                takeAll.contains("headSlot"),
                "the head is furniture again: the button empties the content slots, and the head"
                        + " comes back on close instead of needing a second press");
    }

    @Test
    void emptinessIsAboutTheContent() {
        final String settle = body("private void settle(");

        assertFalse(
                settle.contains("headGone"),
                "a grave whose content slots are empty is finished; requiring the head to be gone"
                        + " too is what made a second press necessary");
        assertTrue(settle.contains("if (!contentGone) {"), "the emptiness decision is the content and nothing else");
    }

    @Test
    void theHeadComesBackOnClose() {
        final String settle = body("private void settle(");
        assertTrue(
                settle.contains("returnHeadToPlayer(inventory, player);"),
                "settle no longer hands the head back when an emptied grave is closed");
        final String returnHead = body("private void returnHeadToPlayer(");
        final int reads = returnHead.indexOf("GravePanel.headSlot(");

        assertTrue(reads >= 0, "returnHeadToPlayer is the one place that takes the head out of the window");
        final String handOut = returnHead.substring(reads);
        assertTrue(
                handOut.contains("player.getInventory().addItem("),
                "the head goes into the inventory of whoever closed the emptied grave");
        assertTrue(
                handOut.contains("dropItemNaturally("),
                "and onto the floor when it does not fit, rather than being deleted");
    }

    /** The text of one method, from its signature to the start of the next member. */
    private String body(final String signature) {
        final String source = read();
        final int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " is gone from " + SOURCE);
        final int next = nextMember(source, start + signature.length());
        return source.substring(start, next);
    }

    private int nextMember(final String source, final int from) {
        final int privateAt = source.indexOf("\n    private ", from);
        final int publicAt = source.indexOf("\n    public ", from);
        if (privateAt < 0) {
            return publicAt < 0 ? source.length() : publicAt;
        }
        if (publicAt < 0) {
            return privateAt;
        }
        return Math.min(privateAt, publicAt);
    }

    /**
     * The same upward search {@link OneGraveOneWindowTest} uses.
     *
     * The working directory of a test is the module, and the path above is written from the repository root.
     */
    private static String read() {
        try {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                candidate = candidate.getParent();
            }
            if (candidate == null) {
                throw new IllegalStateException("no settings.gradle.kts above the working directory");
            }
            return joined(Files.readString(candidate.resolve(SOURCE), StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // palantir-java-format wraps a long call anywhere; the checks read each call as one line.
    private static String joined(final String source) {
        return source.replaceAll("\\(\\s*\\n\\s*", "(")
                .replaceAll("\\s*\\n\\s*\\.", ".")
                .replaceAll("(=|,|->)\\s*\\n\\s*", "$1 ");
    }
}
