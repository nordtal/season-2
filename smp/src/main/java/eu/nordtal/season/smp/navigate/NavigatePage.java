package eu.nordtal.season.smp.navigate;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** The strings pass through {@code MenuFont} and contain no MiniMessage. */
public final class NavigatePage {

    private NavigatePage() {}

    /** Returns the number of pages for {@code total} destinations, at least 1. */
    public static int pages(final int total) {
        return Math.max(1, (total + NavigatePanel.ENTRIES_PER_PAGE - 1) / NavigatePanel.ENTRIES_PER_PAGE);
    }

    public static int clamp(final int page, final int total) {
        return Math.max(0, Math.min(page, pages(total) - 1));
    }

    public static List<NavigationTarget> slice(final List<NavigationTarget> targets, final int page) {
        final int from = Math.min(page * NavigatePanel.ENTRIES_PER_PAGE, targets.size());
        final int to = Math.min(from + NavigatePanel.ENTRIES_PER_PAGE, targets.size());
        return List.copyOf(targets.subList(from, to));
    }

    public static String label(final NavigationTarget target, final Messages messages, final Locale locale) {
        if (target.kind() == NavigationTarget.Kind.POI) {
            // {@code label()} is set only by {@code NavigationTarget.poi()}, so it is non-null for a POI.
            return Objects.requireNonNull(target.label());
        }
        return messages.format(locale, target.name());
    }

    /**
     * Returns the distance in blocks when {@code target} is in {@code world}, otherwise the text for another world.
     * The menu computes it once when it opens.
     */
    public static String distance(
            final NavigationTarget target,
            final String world,
            final double x,
            final double y,
            final double z,
            final GameDocument document) {
        if (!target.isIn(world)) {
            return document.label("other-world");
        }
        final double dx = target.x() - x;
        final double dy = target.y() - y;
        final double dz = target.z() - z;
        return document.label("distance", new NavigateGui.Distance(Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz))));
    }

    public static List<NavigatePanel.Entry> entries(
            final List<NavigationTarget> targets,
            final int page,
            final String world,
            final double x,
            final double y,
            final double z,
            final Optional<NavigationTarget> active,
            final GameDocument document,
            final Messages messages,
            final Locale locale) {
        final List<NavigatePanel.Entry> out = new ArrayList<>();
        for (final NavigationTarget target : slice(targets, page)) {
            out.add(new NavigatePanel.Entry(
                    NavigatePanel.icon(target.kind()),
                    label(target, messages, locale),
                    distance(target, world, x, y, z, document),
                    active.filter(target::equals).isPresent()));
        }
        return List.copyOf(out);
    }
}
