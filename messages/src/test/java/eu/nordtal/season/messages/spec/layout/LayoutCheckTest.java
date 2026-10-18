package eu.nordtal.season.messages.spec.layout;

import static eu.nordtal.season.messages.spec.layout.Line.heading;
import static eu.nordtal.season.messages.spec.layout.Line.list;
import static eu.nordtal.season.messages.spec.layout.Line.text;
import static eu.nordtal.season.messages.spec.layout.Line.title;
import static eu.nordtal.season.messages.spec.layout.Part.container;
import static eu.nordtal.season.messages.spec.layout.Part.gallery;
import static eu.nordtal.season.messages.spec.layout.Part.languages;
import static eu.nordtal.season.messages.spec.layout.Part.row;
import static eu.nordtal.season.messages.spec.layout.Part.sectionWithButton;
import static eu.nordtal.season.messages.spec.layout.Part.sectionWithThumbnail;
import static eu.nordtal.season.messages.spec.layout.Part.separator;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messages.Tone;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A layout's tree against its elements, against the specs of the keys it places and against what Discord draws. */
class LayoutCheckTest {

    static final Layout PRICES = Layout.discord(
            "layouts",
            "prices",
            "Contribution",
            List.of(Element.button("buy", "layouts/buy"), Element.list("tiers", "layouts/tier")),
            container(
                    Tone.BRAND,
                    Part.text(title("layouts/title")),
                    Part.text(heading("layouts/prices"), list("tiers")),
                    Part.text(text("layouts/donation")),
                    separator(),
                    gallery("attachment://prices.png")),
            row("buy"));

    private static List<String> problems(final Layout... layouts) {
        return LayoutCheck.problems(List.of(layouts), LayoutSchema.schemas(List.of(LayoutTexts.class)));
    }

    private static Layout withParts(final List<Element> elements, final Part... parts) {
        return Layout.discord("layouts", "broken", "Contribution", elements, parts);
    }

    @Test
    void aLayoutWhoseTreeFitsItsElementsAndItsKeysHasNoProblem() {
        assertEquals(List.of(), problems(PRICES));
    }

    @Test
    void anElementTheTreeDoesNotPlaceIsMissing() {
        assertEquals(
                List.of("layouts/broken: does not place its element buy"),
                problems(withParts(List.of(Element.button("buy", "layouts/buy")), Part.text(title("layouts/title")))));
    }

    /** A declaration read from JSON can hold an element of no texts, which is reported and does not stop the check. */
    @Test
    void aSelectThatDrawsNoTextIsRefused() {
        assertEquals(
                List.of("layouts/broken: its select pick draws 0 texts"),
                problems(withParts(List.of(new Element("pick", Element.Kind.SELECT, List.of())), row("pick"))));
    }

    @Test
    void anElementPlacedTwiceIsRefused() {
        assertEquals(
                List.of("layouts/broken: places its element buy 2 times"),
                problems(withParts(List.of(Element.button("buy", "layouts/buy")), row("buy"), row("buy"))));
    }

    @Test
    void anElementTheLayoutDoesNotDeclareIsRefused() {
        assertEquals(
                List.of("layouts/broken: places buy, which it does not declare"),
                problems(withParts(List.of(), row("buy"))));
    }

    @Test
    void anElementPlacedWhereItsKindDoesNotFitIsRefused() {
        assertEquals(
                List.of(
                        "layouts/broken: places the select pick where a button belongs",
                        "layouts/broken: places the button buy where a data list belongs"),
                problems(withParts(
                        List.of(Element.select("pick", "layouts/choose"), Element.button("buy", "layouts/buy")),
                        sectionWithButton("pick", Part.text(text("layouts/donation"))),
                        Part.text(list("buy")))));
    }

    @Test
    void aKeyPlacedWhereItsShownDoesNotSayItIsShownIsRefused() {
        assertEquals(
                List.of(
                        "layouts/broken: places layouts/buy as text, but its @Shown names only" + " DISCORD_BUTTON",
                        "layouts/broken: places layouts/title as a button's label, but its @Shown names only"
                                + " DISCORD_TEXT"),
                problems(withParts(
                        List.of(Element.button("go", "layouts/title")), Part.text(title("layouts/buy")), row("go"))));
    }

    @Test
    void aKeyNoBundleDeclaresIsRefused() {
        assertEquals(
                List.of(
                        "layouts/broken: names layouts/nothing, which its bundle does not declare",
                        "layouts/broken: names elsewhere/title, but no bundle elsewhere is on the classpath",
                        "layouts/broken: names title, which is no bundle/key"),
                problems(withParts(
                        List.of(),
                        Part.text(text("layouts/nothing"), text("elsewhere/title")),
                        Part.text(text("title")))));
    }

    @Test
    void aMessageValueThatNamesNoKeysItMayTakeIsRefused() {
        assertEquals(
                List.of("layouts/broken: places layouts/untaken, whose message value what names no keys with @Takes"),
                problems(withParts(List.of(), Part.text(text("layouts/untaken")))));
    }

    @Test
    void twoTextsThatNameOneValueAsDifferentKindsAreRefused() {
        assertEquals(
                List.of("layouts/broken: names {amount} as money in layouts/donation and as number in layouts/count"),
                problems(withParts(List.of(), Part.text(text("layouts/donation"), text("layouts/count")))));
    }

    @Test
    void whatDiscordCannotDrawIsRefused() {
        final Part block = Part.text(text("layouts/donation"));
        assertEquals(
                List.of(
                        "layouts/broken: a container holds another container",
                        "layouts/broken: a section holds 4 text blocks, Discord draws 1 to 3",
                        "layouts/broken: a section holds something other than text blocks",
                        "layouts/broken: a gallery holds 0 images, Discord draws 1 to 10",
                        "layouts/broken: a text block has no lines",
                        "layouts/broken: an image is at ftp://example.org/a.png, not attachment://, upload:// or https://",
                        "layouts/broken: a row holds a select beside something else"),
                problems(withParts(
                        List.of(
                                Element.select("pick", "layouts/choose"),
                                Element.button("buy", "layouts/buy"),
                                Element.button("other", "layouts/buy")),
                        container(Tone.BRAND, container(Tone.BRAND, block)),
                        sectionWithButton("buy", block, block, block, block),
                        sectionWithThumbnail("https://nordtal.eu/a.png", separator()),
                        gallery(),
                        Part.text(),
                        row("pick", "other"),
                        gallery("ftp://example.org/a.png"))));
    }

    @Test
    void aBlockPerLanguageIsPlacedByItsElement() {
        assertEquals(
                List.of(),
                problems(withParts(
                        List.of(Element.languages("each"), Element.button("buy", "layouts/buy")),
                        languages(
                                "each",
                                container(Tone.BRAND, sectionWithButton("buy", Part.text(title("layouts/title"))))))));
    }

    @Test
    void twoLayoutsOfOneNameInOneBundleAreRefused() {
        assertEquals(List.of("layouts/prices: declared more than once"), problems(PRICES, PRICES));
    }

    @Test
    void aRebuildThatMovesTheCodesElementsAndAddsTextsLinksAndUploadsIsSound() {
        final Layout rebuild = Layout.discord(
                "layouts",
                "prices",
                "Contribution",
                List.of(
                        Element.button("buy", "layouts/buy"),
                        Element.list("tiers", "layouts/tier"),
                        Element.link("site", "https://nordtal.eu", Map.of("en", "Site", "de", "Seite"))),
                row("buy", "site"),
                container(
                        Tone.BRAND,
                        Part.text(Line.added(Line.Style.TITLE, Map.of("en", "Hello {player}"))),
                        Part.text(heading("layouts/prices"), list("tiers")),
                        gallery("upload://7")));

        assertEquals(List.of(), LayoutCheck.rebuildProblems(PRICES, rebuild));
        assertEquals(
                List.of(Map.of("en", "Hello {player}"), Map.of("de", "Seite", "en", "Site")), rebuild.addedTexts());
    }

    @Test
    void aRebuildThatDropsAnElementOrPlacesAForeignKeyIsRefused() {
        final Layout rebuild = Layout.discord(
                "layouts",
                "prices",
                "Contribution",
                List.of(Element.list("tiers", "layouts/tier"), Element.link("site", "ftp://nordtal.eu", Map.of())),
                Part.text(text("layouts/count"), list("tiers"), new Line("layouts/title", "tiers", null)),
                row("site"));

        assertEquals(
                List.of(
                        "it does not keep the elements the code fills in as declared",
                        "it places layouts/count, which the layout does not",
                        "a line is not one of a key, a data list or an added text",
                        "its link button site opens no https:// address",
                        "its link button site has no label",
                        "places its element tiers 2 times"),
                LayoutCheck.rebuildProblems(PRICES, rebuild));
    }
}
