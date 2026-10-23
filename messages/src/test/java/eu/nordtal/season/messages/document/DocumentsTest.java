package eu.nordtal.season.messages.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Money;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentsTest {

    record Greeting(
            @Example("Alex") String name, @Example("300") Money amount) {}

    static final DocumentSpec<Greeting> GREETING =
            DocumentSpec.message("test", "greeting", "Welcome", Greeting.class, Slot.button("go"));

    static final DocumentSpec<DocumentSpec.None> ABSENT = DocumentSpec.message("test", "absent", "Welcome");

    @Test
    void aTextIsOneStringOrAListOfVersionsInEachLanguage() {
        final Text text = Document.decode("""
                {"parts": [{"type": "TEXT", "lines": [{"text": {"en": "Hi", "de": ["Hallo", "Moin"]}}]}]}
                """).texts().getFirst();

        assertEquals(Map.of("en", List.of("Hi"), "de", List.of("Hallo", "Moin")), text.languages());
        assertEquals(List.of("Hi"), text.versions("fr"));
        assertTrue(List.of("Hallo", "Moin").contains(text.pick("de", new SplittableRandom(1))));
        assertEquals(
                "{\"parts\":[{\"type\":\"TEXT\",\"lines\":[{\"text\":{\"de\":[\"Hallo\",\"Moin\"],\"en\":\"Hi\"}}]}]}",
                Document.decode(Document.decode("""
                        {"parts": [{"type": "TEXT", "lines": [{"text": {"en": "Hi", "de": ["Hallo", "Moin"]}}]}]}
                        """).json()).json());
    }

    @Test
    void aConditionHoldsOnATruthyValueItsNegationOrAChoice() {
        assertTrue(Condition.holds("amount", Map.of("amount", Money.euroCents(1))));
        assertFalse(Condition.holds("amount", Map.of("amount", Money.euroCents(0))));
        assertTrue(Condition.holds("!name", Map.of("name", "")));
        assertTrue(Condition.holds("phase=closed", Map.of("phase", DocumentCheckTest.Phase.CLOSED)));
        assertFalse(Condition.holds("phase=open", Map.of("phase", DocumentCheckTest.Phase.CLOSED)));
        assertFalse(Condition.holds("missing", Map.of()));
        assertTrue(Condition.holds(null, Map.of()));
    }

    @Test
    void theBuildWritesTheDeclarationsOfTheDocumentsItChecked(@TempDir final Path output) throws IOException {
        Documents.write(output, List.of(GREETING), getClass().getClassLoader());

        final List<DocumentDeclaration> written =
                Documents.decode(Files.readString(output.resolve(Documents.declarations("test"))));
        assertEquals(List.of(GREETING.declaration()), written);
        assertEquals("money", written.getFirst().values().get("amount").kind());
    }

    @Test
    void theBuildRefusesADocumentWithoutItsFile(@TempDir final Path output) {
        final IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> Documents.write(
                        output, List.of(GREETING, ABSENT), getClass().getClassLoader()));

        assertTrue(
                refused.getMessage().contains("test/absent: documents/test/absent.json is missing"),
                refused.getMessage());
    }

    @Test
    void aHolderDeclaresItsStaticFinalDocuments() {
        assertEquals(List.of(GREETING, ABSENT), Documents.declared(DocumentsTest.class));
    }
}
