package eu.nordtal.season.messages.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentMergeTest {

    private static final String PACKAGED = """
            {"parts": [
              {"type": "TEXT", "lines": [{"text": {"en": "Choose"}}]},
              {"type": "ROW", "items": [{"slot": "buy", "label": {"en": "Buy"}}, {"slot": "change", "label": {"en": "Change"}}]}]}
            """;

    private static Documents.Packaged packaged(final Slot... slots) {
        return new Documents.Packaged(
                DocumentSpec.message("test", "merge", "Purchase", slots)
                        .titled("Merge")
                        .declaration(),
                Document.decode(PACKAGED));
    }

    @Test
    void aDocumentWrittenOverThisReleaseIsTakenAsItIs() {
        final Documents.Packaged packaged = packaged(Slot.button("buy"), Slot.button("change"));
        final Document written = Document.decode(PACKAGED.replace("Choose", "Pick one"));

        assertEquals(
                new DocumentMerge.Merged(written, List.of()), DocumentMerge.merge(packaged, written, packaged.hash()));
    }

    @Test
    void aRemovedSlotIsTakenOutAndAnAddedOneIsPlacedAsTheReleasePlacesIt() {
        final Document written = Document.decode("""
                {"parts": [
                  {"type": "TEXT", "lines": [{"text": {"en": "Pick one"}}]},
                  {"type": "ROW", "items": [{"slot": "buy", "label": {"en": "Get it"}}, {"slot": "gone", "label": {"en": "Gone"}}]}]}
                """);

        final DocumentMerge.Merged merged =
                DocumentMerge.merge(packaged(Slot.button("buy"), Slot.button("change")), written, "an older release");

        assertEquals(
                List.of(
                        new DocumentMerge.Adaptation(DocumentMerge.Change.SLOT_REMOVED, "gone"),
                        new DocumentMerge.Adaptation(DocumentMerge.Change.SLOT_ADDED, "change")),
                merged.adaptations());
        assertEquals(Document.decode("""
                        {"parts": [
                          {"type": "TEXT", "lines": [{"text": {"en": "Pick one"}}]},
                          {"type": "ROW", "items": [{"slot": "buy", "label": {"en": "Get it"}}]},
                          {"type": "ROW", "items": [{"slot": "change", "label": {"en": "Change"}}]}]}
                        """), merged.document());
    }

    @Test
    void aDocumentThatStillDoesNotFitIsSetAside() {
        final Document written = Document.decode("""
                {"parts": [{"type": "TEXT", "lines": [{"text": {"en": "Pick {nothing}"}}]},
                  {"type": "ROW", "items": [{"slot": "buy", "label": {"en": "Buy"}}, {"slot": "change", "label": {"en": "Change"}}]}]}
                """);
        final Documents.Packaged packaged = packaged(Slot.button("buy"), Slot.button("change"));

        final DocumentMerge.Merged merged = DocumentMerge.merge(packaged, written, "an older release");

        assertEquals(packaged.document(), merged.document());
        assertEquals(
                DocumentMerge.Change.SET_ASIDE, merged.adaptations().getFirst().change());
    }

    @Test
    void aMenusRemovedItemIsTakenOutAndAnAddedOneIsAppended() {
        final Document menu = Document.decode("""
                {"parts": [
                  {"type": "TEXT", "lines": [{"style": "TITLE", "text": {"en": "Wheel"}}]},
                  {"type": "ITEM", "slot": "hub", "lines": [{"text": {"en": "Hub"}}]},
                  {"type": "LABEL", "slot": "left", "lines": [{"text": {"en": "Left"}}]}]}
                """);
        final Documents.Packaged packaged = new Documents.Packaged(
                DocumentSpec.menu(
                                "test",
                                "wheel",
                                "Menus",
                                DocumentSpec.None.class,
                                1,
                                Slot.item("hub").at(List.of(4)),
                                Slot.label("left"))
                        .titled("Wheel")
                        .declaration(),
                menu);
        final Document written = Document.decode("""
                {"parts": [
                  {"type": "TEXT", "lines": [{"style": "TITLE", "text": {"en": "Spin"}}]},
                  {"type": "ITEM", "slot": "gone", "lines": [{"text": {"en": "Gone"}}]},
                  {"type": "ITEM", "slot": "hub", "lines": [{"text": {"en": "Centre"}}]}]}
                """);

        final DocumentMerge.Merged merged = DocumentMerge.merge(packaged, written, "an older release");

        assertEquals(
                List.of(
                        new DocumentMerge.Adaptation(DocumentMerge.Change.SLOT_REMOVED, "gone"),
                        new DocumentMerge.Adaptation(DocumentMerge.Change.SLOT_ADDED, "left")),
                merged.adaptations());
        assertEquals(Document.decode("""
                        {"parts": [
                          {"type": "TEXT", "lines": [{"style": "TITLE", "text": {"en": "Spin"}}]},
                          {"type": "ITEM", "slot": "hub", "lines": [{"text": {"en": "Centre"}}]},
                          {"type": "LABEL", "slot": "left", "lines": [{"text": {"en": "Left"}}]}]}
                        """), merged.document());
    }
}
