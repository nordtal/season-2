package eu.nordtal.season.papercommon.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PagingTest {

    @Test
    void anEmptyOrShortListHasOnePageAndNoPager() {
        assertEquals(1, Paging.of(0, 3).pages());
        assertFalse(Paging.of(3, 3).paged());
        assertTrue(Paging.of(4, 3).paged());
    }

    @Test
    void nextWrapsToTheFirstPageAndPreviousToTheLast() {
        final Paging paging = Paging.of(7, 3);

        assertEquals(3, paging.pages());
        assertEquals(1, paging.next(0));
        assertEquals(0, paging.next(2));
        assertEquals(2, paging.previous(0));
    }

    @Test
    void aPageBeyondAListThatShrankShowsItsLastPage() {
        final Paging paging = Paging.of(4, 3);

        assertEquals(1, paging.clamp(5));
        assertEquals(List.of("d"), paging.slice(List.of("a", "b", "c", "d"), 5));
        assertEquals(List.of("a", "b", "c"), paging.slice(List.of("a", "b", "c", "d"), 0));
    }
}
