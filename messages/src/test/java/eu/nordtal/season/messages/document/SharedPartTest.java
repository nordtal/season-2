package eu.nordtal.season.messages.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedPartTest {

    private static final String WRITTEN = """
            {"parts": [
              {"type": "TEXT", "lines": [{"text": {"en": "Hello"}}]},
              {"type": "CONTAINER", "parts": [
                {"type": "TEXT", "shared": "rules", "lines": [{"text": {"en": "Be kind"}}]}]},
              {"type": "TEXT", "shared": "footer", "lines": [{"text": {"en": "Bye"}}]}]}
            """;

    @Test
    void everyCopyOfASharedPartIsReplacedWhereverItIsNested() {
        final Part saved = Document.decode("""
                {"parts": [{"type": "TEXT", "lines": [{"text": {"en": "Be kind, always"}}]}]}""").parts().getFirst();

        assertEquals(
                Document.decode(WRITTEN.replace("Be kind", "Be kind, always")),
                Document.decode(WRITTEN).withSharedPart("rules", saved));
    }

    @Test
    void aRemovedSharedPartLeavesItsCopiesUnmarked() {
        assertEquals(
                Document.decode(WRITTEN.replace("\"shared\": \"rules\", ", "")),
                Document.decode(WRITTEN).withSharedPart("rules", null));
    }

    @Test
    void aSharedPartHoldsNoOtherSharedPart() {
        final Part container = Document.decode(WRITTEN).parts().get(1).marked("rules-box");

        assertEquals(Set.of("rules-box"), new Document(List.of(container)).sharedParts());
        assertEquals(Set.of("footer", "rules"), Document.decode(WRITTEN).sharedParts());
    }
}
