package eu.nordtal.season.smp.navigate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.smp.menu.PanelWalk;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NavigatePageTest {

    private static final Messages MESSAGES =
            Messages.load(NavigatePageTest.class.getClassLoader(), "messages/smp", Locale.GERMAN, Locale.ENGLISH);

    private static final GameDocument DOCUMENT =
            PanelWalk.document(NavigateGui.DOCUMENT, Locale.ENGLISH, new NavigateGui.Window(1, 3));

    private static final String WORLD = "nordtal";

    @Test
    void thePageCountRoundsUp() {
        assertEquals(
                1,
                NavigatePage.pages(0),
                "zero destinations is impossible in practice - the world spawn is always there -"
                        + " but a page count of zero would put the window at page -1");
        assertEquals(1, NavigatePage.pages(1));
        assertEquals(1, NavigatePage.pages(5));
        assertEquals(2, NavigatePage.pages(6));
        assertEquals(2, NavigatePage.pages(10));
        assertEquals(3, NavigatePage.pages(11));
    }

    @Test
    void aStalePageIsClamped() {
        assertEquals(0, NavigatePage.clamp(-1, 12));
        assertEquals(2, NavigatePage.clamp(9, 12));
        // A player on page three sees a list one entry shorter than the button was drawn for.
        assertEquals(1, NavigatePage.clamp(2, 7));
    }

    @Test
    void theLastPageIsShort() {
        final List<NavigationTarget> twelve = targets(12);
        assertEquals(5, NavigatePage.slice(twelve, 0).size());
        assertEquals(5, NavigatePage.slice(twelve, 1).size());
        assertEquals(2, NavigatePage.slice(twelve, 2).size());
        assertEquals(
                List.of(),
                NavigatePage.slice(twelve, 3),
                "a page past the end is empty rather than an IndexOutOfBoundsException in a menu");
        assertEquals("POI 11", NavigatePage.slice(twelve, 2).get(1).label());
    }

    @Test
    void anotherWorldIsNotADistance() {
        final NavigationTarget elsewhere = NavigationTarget.poi(UUID.randomUUID(), "Hub", "farm", 0, 64, 0);
        assertEquals(
                DOCUMENT.label("other-world"),
                NavigatePage.distance(elsewhere, WORLD, 0, 64, 0, DOCUMENT),
                "a straight-line number to a point in a dimension the player is not in is worse"
                        + " than no number: it reads as walkable and it is not");
    }

    @Test
    void theDistanceIsWhatItSays() {
        final NavigationTarget target = NavigationTarget.poi(UUID.randomUUID(), "Mine", WORLD, 30, 64, 40);
        assertEquals("50 m", NavigatePage.distance(target, WORLD, 0, 64, 0, DOCUMENT));
        // Height counts, so a point at the bottom of a shaft is not 0 m away from the surface.
        final NavigationTarget below = NavigationTarget.poi(UUID.randomUUID(), "Shaft", WORLD, 0, 4, 0);
        assertEquals("60 m", NavigatePage.distance(below, WORLD, 0, 64, 0, DOCUMENT));
    }

    @Test
    void theActiveMarkerFollowsThePage() {
        final List<NavigationTarget> twelve = targets(12);
        final NavigationTarget active = twelve.get(7);

        final List<NavigatePanel.Entry> first = entries(twelve, 0, Optional.of(active));
        assertTrue(
                first.stream().noneMatch(NavigatePanel.Entry::active),
                "the active destination is not on page one, so nothing on page one is framed");

        final List<NavigatePanel.Entry> second = entries(twelve, 1, Optional.of(active));
        assertEquals(
                List.of(false, false, true, false, false),
                second.stream().map(NavigatePanel.Entry::active).toList(),
                "the eighth destination is the third row of the second page - the marker follows"
                        + " the destination and not the index within the whole list");
    }

    @Test
    void nothingIsActiveWhenNavigationIsOff() {
        for (final NavigatePanel.Entry entry : entries(targets(12), 0, Optional.empty())) {
            assertFalse(entry.active());
        }
    }

    @Test
    void theKindsAreDistinguishable() {
        assertEquals(Glyphs.GUI_ROW_ICON_SPAWN, NavigatePanel.icon(NavigationTarget.Kind.WORLD_SPAWN));
        assertEquals(Glyphs.GUI_ROW_ICON_DEATH, NavigatePanel.icon(NavigationTarget.Kind.LAST_DEATH));
        assertEquals(Glyphs.GUI_ROW_ICON_POI, NavigatePanel.icon(NavigationTarget.Kind.POI));

        // The label of a point is its name. The two built-in kinds have a message key.
        assertEquals(
                "Baeckerei",
                NavigatePage.label(
                        NavigationTarget.poi(UUID.randomUUID(), "Baeckerei", WORLD, 0, 0, 0),
                        MESSAGES,
                        Locale.ENGLISH));
        assertEquals(
                MESSAGES.get(Locale.GERMAN, "smp.navigate.world-spawn"),
                NavigatePage.label(NavigationTarget.worldSpawn(WORLD, 0, 0, 0), MESSAGES, Locale.GERMAN));
    }

    @Test
    void thePageLabelIsHumanNumbered() {
        assertEquals("1/3", DOCUMENT.label("page"));
        assertEquals(
                "1/1",
                PanelWalk.document(NavigateGui.DOCUMENT, Locale.GERMAN, new NavigateGui.Window(1, 1))
                        .label("page"));
    }

    private static List<NavigatePanel.Entry> entries(
            final List<NavigationTarget> targets, final int page, final Optional<NavigationTarget> active) {
        return NavigatePage.entries(targets, page, WORLD, 0, 64, 0, active, DOCUMENT, MESSAGES, Locale.ENGLISH);
    }

    private static List<NavigationTarget> targets(final int count) {
        final List<NavigationTarget> out = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            out.add(NavigationTarget.poi(UUID.randomUUID(), "POI " + index, WORLD, index * 10, 64, 0));
        }
        return List.copyOf(out);
    }
}
