package eu.nordtal.season.papercommon.dialog;

import java.util.List;

/** The pages of a dialog's list: at least one, next wraps to the first after the last and previous the other way. */
public record Paging(int pages, int each) {

    public Paging {
        if (pages < 1 || each < 1) {
            throw new IllegalArgumentException("a list has a page and a row a page at least");
        }
    }

    /** The pages of {@code count} entries, {@code each} a page. */
    public static Paging of(final int count, final int each) {
        return new Paging(Math.max(1, Math.ceilDiv(Math.max(0, count), each)), each);
    }

    /** Whether the list shows a pager: only when it has more than one page. */
    public boolean paged() {
        return pages > 1;
    }

    /** Returns {@code page} within the list, for a list that shrank since it was drawn. */
    public int clamp(final int page) {
        return Math.clamp(page, 0, pages - 1);
    }

    public int next(final int page) {
        return (clamp(page) + 1) % pages;
    }

    public int previous(final int page) {
        return (clamp(page) + pages - 1) % pages;
    }

    /** Returns the entries of {@code page}, counted from 0. */
    public <T> List<T> slice(final List<T> entries, final int page) {
        final int from = Math.min(clamp(page) * each, entries.size());
        return entries.subList(from, Math.min(from + each, entries.size()));
    }
}
