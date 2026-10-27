package eu.nordtal.season.messages.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedPartTest {

    private static final String WRITTEN = """
            {"parts": [
              {"type": "TEXT", "lines": [{"text": {"en": "Hello"}}]},
              {"type": "CONTAINER", "parts": [{"type": "TEXT", "shared": "rules", "when": "member"}]},
              {"type": "CONTAINER", "shared": "footer"}]}
            """;

    private static final Map<String, Part> SHARED = Map.of(
            "rules", part("""
                    {"type": "TEXT", "lines": [{"text": {"en": "Be kind"}}]}"""),
            "footer", part("""
                    {"type": "CONTAINER", "parts": [{"type": "TEXT", "shared": "rules"}]}"""));

    @Test
    void aSharedPartIsDrawnWhereADocumentNamesItWithTheConditionOfThePlaceThatNamesIt() {
        assertEquals(Document.decode("""
                        {"parts": [
                          {"type": "TEXT", "lines": [{"text": {"en": "Hello"}}]},
                          {"type": "CONTAINER", "parts": [
                            {"type": "TEXT", "when": "member", "lines": [{"text": {"en": "Be kind"}}]}]},
                          {"type": "CONTAINER", "parts": [{"type": "TEXT", "lines": [{"text": {"en": "Be kind"}}]}]}]}
                        """), Document.decode(WRITTEN).resolved(SHARED));
    }

    @Test
    void aDocumentReachesEverySharedPartItNamesItselfOrThroughAnother() {
        assertEquals(
                Set.of("footer", "rules"),
                Document.decode(WRITTEN).sharedParts(Map.of("footer", SHARED.get("footer"))));
        assertEquals(Set.of("footer"), new Document(List.of(part("""
                {"type": "CONTAINER", "shared": "footer"}"""))).sharedParts(Map.of()));
    }

    @Test
    void aNameNoSharedPartHoldsAndANameThatReachesItselfDrawNothing() {
        final Map<String, Part> loop = Map.of("footer", part("""
                {"type": "CONTAINER", "parts": [{"type": "TEXT", "shared": "footer"}]}"""));

        assertEquals(Document.decode("""
                        {"parts": [{"type": "TEXT", "lines": [{"text": {"en": "Hello"}}]}, {"type": "CONTAINER", "parts": []},
                          {"type": "CONTAINER", "parts": []}]}"""), Document.decode(WRITTEN).resolved(loop));
    }

    private static Part part(final String json) {
        return Document.decode("{\"parts\": [" + json + "]}").parts().getFirst();
    }
}
