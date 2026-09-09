package eu.nordtal.s2.smp.navigate;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.Messages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one page of {@code /navigate} contains, which is the half of that menu a test can hold.
 *
 * <p>Paging is new on 2026-09-09 and it replaced something that quietly did the wrong thing: the
 * window used to be sized to the destination count and then <em>truncated</em> at whatever six rows
 * held, so a server with more than forty-five POIs simply stopped showing some of them and said
 * nothing at all about it. Everything below is a case where the failure would look like the menu
 * working - the last page one entry short, the active marker on the wrong row after a page turn, a
 * distance measured through a dimension the player is not standing in.</p>
 */
class NavigatePageTest {

    private static final Messages MESSAGES = Messages.load(NavigatePageTest.class.getClassLoader(),
            "messages/smp", Locale.GERMAN, Locale.ENGLISH);

    private static final String WORLD = "nordtal";

    @Test
    @DisplayName("an empty list is still one page, and a full one rounds up")
    void thePageCountRoundsUp() {
        assertEquals(1, NavigatePage.pages(0),
                "zero destinations is impossible in practice - the world spawn is always there -"
                        + " but a page count of zero would put the window at page -1");
        assertEquals(1, NavigatePage.pages(1));
        assertEquals(1, NavigatePage.pages(5));
        assertEquals(2, NavigatePage.pages(6));
        assertEquals(2, NavigatePage.pages(10));
        assertEquals(3, NavigatePage.pages(11));
    }

    @Test
    @DisplayName("a page number out of range is pulled back in rather than opening an empty window")
    void aStalePageIsClamped() {
        assertEquals(0, NavigatePage.clamp(-1, 12));
        assertEquals(2, NavigatePage.clamp(9, 12));
        // The case that actually happens: somebody is on page three when a POI is deleted and the
        // list they are holding is one shorter than the one the button was drawn for.
        assertEquals(1, NavigatePage.clamp(2, 7));
    }

    @Test
    @DisplayName("the last page carries the remainder and nothing beyond it")
    void theLastPageIsShort() {
        final List<NavigationTarget> twelve = targets(12);
        assertEquals(5, NavigatePage.slice(twelve, 0).size());
        assertEquals(5, NavigatePage.slice(twelve, 1).size());
        assertEquals(2, NavigatePage.slice(twelve, 2).size());
        assertEquals(List.of(), NavigatePage.slice(twelve, 3),
                "a page past the end is empty rather than an IndexOutOfBoundsException in a menu");
        assertEquals("POI 11", NavigatePage.slice(twelve, 2).get(1).label());
    }

    @Test
    @DisplayName("a destination in another world is named, not measured")
    void anotherWorldIsNotADistance() {
        final NavigationTarget elsewhere = NavigationTarget.poi(UUID.randomUUID(), "Hub",
                "farm", 0, 64, 0);
        assertEquals(MESSAGES.get(Locale.ENGLISH, "smp.navigate.other-world"),
                NavigatePage.distance(elsewhere, WORLD, 0, 64, 0, MESSAGES, Locale.ENGLISH),
                "a straight-line number to a point in a dimension the player is not in is worse"
                        + " than no number: it reads as walkable and it is not");
    }

    @Test
    @DisplayName("a distance is three-dimensional and rounded")
    void theDistanceIsWhatItSays() {
        final NavigationTarget target = NavigationTarget.poi(UUID.randomUUID(), "Mine",
                WORLD, 30, 64, 40);
        assertEquals("50 m", NavigatePage.distance(target, WORLD, 0, 64, 0, MESSAGES, Locale.ENGLISH));
        // Height counts. A POI at the bottom of a shaft is not "0 m away" from the surface.
        final NavigationTarget below = NavigationTarget.poi(UUID.randomUUID(), "Shaft",
                WORLD, 0, 4, 0);
        assertEquals("60 m", NavigatePage.distance(below, WORLD, 0, 64, 0, MESSAGES, Locale.ENGLISH));
    }

    @Test
    @DisplayName("the active destination is marked on whichever page it turns up on")
    void theActiveMarkerFollowsThePage() {
        final List<NavigationTarget> twelve = targets(12);
        final NavigationTarget active = twelve.get(7);

        final List<NavigatePanel.Entry> first = entries(twelve, 0, Optional.of(active));
        assertTrue(first.stream().noneMatch(NavigatePanel.Entry::active),
                "the active destination is not on page one, so nothing on page one is framed");

        final List<NavigatePanel.Entry> second = entries(twelve, 1, Optional.of(active));
        assertEquals(List.of(false, false, true, false, false),
                second.stream().map(NavigatePanel.Entry::active).toList(),
                "the eighth destination is the third row of the second page - the marker follows"
                        + " the destination and not the index within the whole list");
    }

    @Test
    @DisplayName("with navigation off nothing is framed at all")
    void nothingIsActiveWhenNavigationIsOff() {
        for (final NavigatePanel.Entry entry : entries(targets(12), 0, Optional.empty())) {
            assertFalse(entry.active());
        }
    }

    @Test
    @DisplayName("each kind of destination is drawn with its own pictogram, and named its own way")
    void theKindsAreDistinguishable() {
        assertEquals(Glyphs.GUI_ROW_ICON_SPAWN, NavigatePanel.icon(NavigationTarget.Kind.WORLD_SPAWN));
        assertEquals(Glyphs.GUI_ROW_ICON_DEATH, NavigatePanel.icon(NavigationTarget.Kind.LAST_DEATH));
        assertEquals(Glyphs.GUI_ROW_ICON_POI, NavigatePanel.icon(NavigationTarget.Kind.POI));

        // A POI's label IS its name; the two built-in kinds carry a message key instead, and
        // printing that key on the row is exactly what happens if this branch is dropped.
        assertEquals("Baeckerei", NavigatePage.label(
                NavigationTarget.poi(UUID.randomUUID(), "Baeckerei", WORLD, 0, 0, 0),
                MESSAGES, Locale.ENGLISH));
        assertEquals(MESSAGES.get(Locale.GERMAN, "smp.navigate.world-spawn"), NavigatePage.label(
                NavigationTarget.worldSpawn(WORLD, 0, 0, 0), MESSAGES, Locale.GERMAN));
    }

    @Test
    @DisplayName("the page label counts from one, because a player does")
    void thePageLabelIsHumanNumbered() {
        assertEquals("1/3", NavigatePage.pageLabel(0, 12, MESSAGES, Locale.ENGLISH));
        assertEquals("3/3", NavigatePage.pageLabel(2, 12, MESSAGES, Locale.GERMAN));
        assertEquals("1/1", NavigatePage.pageLabel(0, 1, MESSAGES, Locale.ENGLISH));
    }

    // --- helpers ---------------------------------------------------------------------------

    private static List<NavigatePanel.Entry> entries(final List<NavigationTarget> targets,
                                                     final int page,
                                                     final Optional<NavigationTarget> active) {
        return NavigatePage.entries(targets, page, WORLD, 0, 64, 0, active, MESSAGES, Locale.ENGLISH);
    }

    private static List<NavigationTarget> targets(final int count) {
        final List<NavigationTarget> out = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            out.add(NavigationTarget.poi(UUID.randomUUID(), "POI " + index, WORLD,
                    index * 10, 64, 0));
        }
        return List.copyOf(out);
    }
}
