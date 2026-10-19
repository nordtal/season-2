package eu.nordtal.season.limbo.waiting;

import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.nordtal.season.limboprotocol.WaitReason;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The waiting room's packaged titles fit the screen at full size. */
class WaitingTextTest {

    @Test
    void everyTitleIsShortEnoughToBeDrawnAtFullSize() {
        // A title scaled down to fit reads as broken on a black screen; forty is roughly the full-size limit.
        for (final WaitReason reason : WaitReason.values()) {
            final String resource = WaitingRoom.screen(reason, null).document().resource();
            final List<Line> lines = Document.decode(Documents.read(WaitingTextTest.class.getClassLoader(), resource))
                    .parts()
                    .getFirst()
                    .allLines();
            // The last line is the subtitle; the update screen has a title with and one without its percentage.
            for (final Line title : lines.subList(0, lines.size() - 1)) {
                for (final String language : List.of("en", "de")) {
                    for (final String raw : title.text().versions(language)) {
                        final String drawn = drawn(raw);
                        assertFalse(
                                drawn.length() > 40,
                                language + " " + reason + " title is " + drawn.length() + " characters: " + drawn);
                    }
                }
            }
        }
    }

    /** The title without its MiniMessage tags and with each value at 100, which is what the client has to fit. */
    private static String drawn(final String raw) {
        return raw.replaceAll("</?[a-zA-Z_#][a-zA-Z0-9_:.#'\\-]*>", "").replaceAll("\\{[^}]*}", "100");
    }
}
