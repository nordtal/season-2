package eu.nordtal.season.messages.spec.layout;

import static eu.nordtal.season.messages.spec.layout.Line.heading;
import static eu.nordtal.season.messages.spec.layout.Line.list;
import static eu.nordtal.season.messages.spec.layout.Line.title;
import static eu.nordtal.season.messages.spec.layout.Part.container;
import static eu.nordtal.season.messages.spec.layout.Part.row;
import static eu.nordtal.season.messages.spec.layout.Part.sectionWithButton;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.layout.LayoutMerge.Adaptation;
import eu.nordtal.season.messages.spec.layout.LayoutMerge.Change;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An editor's rebuild carried over a release that changed the packaged layout it was written over. */
class LayoutMergeTest {

    private static final Layout BEFORE = Layout.discord(
            "layouts",
            "prices",
            "Contribution",
            List.of(Element.button("buy", "layouts/buy"), Element.list("tiers", "layouts/tier")),
            container(
                    Tone.BRAND, Part.text(title("layouts/title")), Part.text(heading("layouts/prices"), list("tiers"))),
            row("buy"));

    /** The next release: the price list and the donation text go, a renew button and a donation list come. */
    private static final Layout AFTER = Layout.discord(
            "layouts",
            "prices",
            "Contribution",
            List.of(
                    Element.button("buy", "layouts/buy"),
                    Element.button("renew", "layouts/renew"),
                    Element.list("donations", "layouts/tier")),
            container(Tone.BRAND, Part.text(title("layouts/title"))),
            row("buy", "renew"));

    private static final Line ADDED = Line.added(null, Map.of("en", "Thanks!"));

    /** The editor moved the button up beside the title, added a text, and kept the prices under it. */
    private static final List<Part> REBUILD = List.of(
            sectionWithButton("buy", Part.text(title("layouts/title"), ADDED)),
            Part.text(heading("layouts/prices"), list("tiers")));

    @Test
    void aRebuildOverTheShippedLayoutIsKeptAsItIs() {
        final LayoutMerge.Merged merged = LayoutMerge.merge(BEFORE, REBUILD, List.of(), LayoutSchema.hash(BEFORE));

        assertEquals(BEFORE.rebuilt(REBUILD, List.of()), merged.layout());
        assertEquals(List.of(), merged.adaptations());
    }

    @Test
    void aReleaseAddsItsNewElementsAtTheEndAndDropsWhatItRemoved() {
        final LayoutMerge.Merged merged = LayoutMerge.merge(AFTER, REBUILD, List.of(), LayoutSchema.hash(BEFORE));

        assertEquals(
                List.of(
                        sectionWithButton("buy", Part.text(title("layouts/title"), ADDED)),
                        Part.text(list("donations")),
                        row("renew")),
                merged.layout().parts(),
                "the editor's order and added text stay, the prices heading and list go, the new ones follow");
        assertEquals(List.of(), LayoutCheck.rebuildProblems(AFTER, merged.layout()));
        assertEquals(
                List.of(
                        new Adaptation(Change.KEY_REMOVED, "layouts/prices"),
                        new Adaptation(Change.ELEMENT_REMOVED, "tiers"),
                        new Adaptation(Change.ELEMENT_ADDED, "renew"),
                        new Adaptation(Change.ELEMENT_ADDED, "donations")),
                merged.adaptations());
    }

    @Test
    void aRemovedButtonLeavesTheTextsOfItsSectionInItsPlace() {
        final Layout withoutBuy = Layout.discord(
                "layouts",
                "prices",
                "Contribution",
                List.of(Element.list("tiers", "layouts/tier")),
                Part.text(title("layouts/title"), heading("layouts/prices"), list("tiers")));

        final LayoutMerge.Merged merged = LayoutMerge.merge(withoutBuy, REBUILD, List.of(), LayoutSchema.hash(BEFORE));

        assertEquals(
                List.of(Part.text(title("layouts/title"), ADDED), REBUILD.get(1)),
                merged.layout().parts());
        assertEquals(List.of(new Adaptation(Change.ELEMENT_REMOVED, "buy")), merged.adaptations());
    }

    @Test
    void anElementThatChangedItsKindIsPlacedAnewAtTheEnd() {
        final Layout buyIsASelect = Layout.discord(
                "layouts",
                "prices",
                "Contribution",
                List.of(Element.select("buy", "layouts/buy"), Element.list("tiers", "layouts/tier")),
                Part.text(title("layouts/title"), heading("layouts/prices"), list("tiers")),
                row("buy"));

        final LayoutMerge.Merged merged =
                LayoutMerge.merge(buyIsASelect, REBUILD, List.of(), LayoutSchema.hash(BEFORE));

        assertEquals(
                List.of(Part.text(title("layouts/title"), ADDED), REBUILD.get(1), row("buy")),
                merged.layout().parts());
        assertEquals(List.of(), LayoutCheck.rebuildProblems(buyIsASelect, merged.layout()));
    }

    @Test
    void aModalsNewFieldIsAddedInARowOfItsOwn() {
        final Layout twoFields = Layout.modal(
                "layouts",
                "code",
                "Link",
                "layouts/dialog",
                Element.input("code", "layouts/field"),
                Element.input("note", "layouts/field"));

        final LayoutMerge.Merged merged = LayoutMerge.merge(
                twoFields, LayoutCheckTest.CODE.parts(), List.of(), LayoutSchema.hash(LayoutCheckTest.CODE));

        assertEquals(twoFields.parts(), merged.layout().parts());
        assertEquals(List.of(new Adaptation(Change.ELEMENT_ADDED, "note")), merged.adaptations());
    }

    @Test
    void aRebuildThatCannotBeCarriedOverIsSetAsideForThePackagedLayout() {
        // An editor's link button whose id the release now declares for its own element.
        final Element clash = Element.link("renew", "https://nordtal.eu", Map.of("en", "Renew"));

        final LayoutMerge.Merged merged = LayoutMerge.merge(
                AFTER, List.of(Part.text(title("layouts/title")), row("buy", "renew")), List.of(clash), "a".repeat(64));

        assertEquals(AFTER, merged.layout());
        assertEquals(Change.SET_ASIDE, merged.adaptations().getFirst().change());
    }
}
