package eu.nordtal.s2.smp.navigate;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.message.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@code /navigate}'s window shows on one page, decided without Bukkit so a test can hold it.
 *
 * Its strings go through {@code MenuFont} and are plain bundle values, never MiniMessage.
 */
public final class NavigatePage {

    private NavigatePage() {}

    /** How many pages {@code total} destinations fill, at least one so an empty list still draws. */
    public static int pages(final int total) {
        return Math.max(1, (total + NavigatePanel.ENTRIES_PER_PAGE - 1) / NavigatePanel.ENTRIES_PER_PAGE);
    }

    /** Clamps a page number into range, so a stale click cannot open a page that is not there. */
    public static int clamp(final int page, final int total) {
        return Math.max(0, Math.min(page, pages(total) - 1));
    }

    /** The destinations on {@code page}, in order: possibly fewer than a full page, never more. */
    public static List<NavigationTarget> slice(final List<NavigationTarget> targets, final int page) {
        final int from = Math.min(page * NavigatePanel.ENTRIES_PER_PAGE, targets.size());
        final int to = Math.min(from + NavigatePanel.ENTRIES_PER_PAGE, targets.size());
        return List.copyOf(targets.subList(from, to));
    }

    /** What a destination is called on its row: a POI's own text, or the built-in kind's message. */
    public static String label(final NavigationTarget target, final Messages messages, final Locale locale) {
        if (target.kind() == NavigationTarget.Kind.POI) {
            // Non-null exactly when kind() is POI: NavigationTarget.poi() is the only factory that supplies one.
            return Objects.requireNonNull(target.label());
        }
        return messages.format(locale, target.name());
    }

    /**
     * What stands at the right edge of a row: blocks when in the player's world, else the words for "another world".
     *
     * The distance is computed when the menu opens; the HUD is what tracks the target live.
     */
    public static String distance(
            final NavigationTarget target,
            final String world,
            final double x,
            final double y,
            final double z,
            final Messages messages,
            final Locale locale) {
        if (!target.isIn(world)) {
            return messages.format(locale, MESSAGES.smp().navigate().otherWorld());
        }
        final double dx = target.x() - x;
        final double dy = target.y() - y;
        final double dz = target.z() - z;
        return messages.format(
                locale, MESSAGES.smp().navigate().distance(Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz))));
    }

    /** Every drawn row of one page, in order. */
    public static List<NavigatePanel.Entry> entries(
            final List<NavigationTarget> targets,
            final int page,
            final String world,
            final double x,
            final double y,
            final double z,
            final Optional<NavigationTarget> active,
            final Messages messages,
            final Locale locale) {
        final List<NavigatePanel.Entry> out = new ArrayList<>();
        for (final NavigationTarget target : slice(targets, page)) {
            out.add(new NavigatePanel.Entry(
                    NavigatePanel.icon(target.kind()),
                    label(target, messages, locale),
                    distance(target, world, x, y, z, messages, locale),
                    active.filter(target::equals).isPresent()));
        }
        return List.copyOf(out);
    }

    /** The {@code 2/3} between the two page buttons. */
    public static String pageLabel(final int page, final int total, final Messages messages, final Locale locale) {
        return messages.format(locale, MESSAGES.smp().navigate().page(page + 1, pages(total)));
    }
}
