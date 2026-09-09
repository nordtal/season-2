package eu.nordtal.s2.smp.navigate;

import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.message.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What {@code /navigate}'s window shows on one page - decided here, with no Bukkit in sight.
 *
 * <h2>Why this is its own class</h2>
 * Everything a page is made of is arithmetic and text: which five of the destinations, how far away
 * each is, what the entry is called and which one is already being pointed at. None of it needs a
 * world, a player or an inventory, and all of it is the part that can be wrong in a way nobody
 * notices - a distance rounded the wrong way, the last page silently empty, the active entry marked
 * on the wrong row after a page turn. {@link NavigateGui} keeps the half that genuinely needs a
 * running server and this keeps the half a test can hold.
 *
 * <h2>The strings here are drawn in the five-pixel sheet</h2>
 * They are therefore <b>plain, tagless bundle values</b> and not MiniMessage: what
 * {@link eu.nordtal.s2.common.menu.MenuFont} draws is characters, and a {@code <gray>} in one of
 * these keys would be folded to capitals and printed. The tooltips under the slots are ordinary
 * components and do go through {@code MessageRenderer}; the two live side by side in
 * {@link NavigateGui} and it is worth knowing which is which.
 */
public final class NavigatePage {

    private NavigatePage() {
    }

    /** How many pages {@code total} destinations fill - never fewer than one, so an empty list still draws. */
    public static int pages(final int total) {
        return Math.max(1, (total + NavigatePanel.ENTRIES_PER_PAGE - 1)
                / NavigatePanel.ENTRIES_PER_PAGE);
    }

    /** Clamps a page number into range, so a stale click cannot open a page that is not there. */
    public static int clamp(final int page, final int total) {
        return Math.max(0, Math.min(page, pages(total) - 1));
    }

    /** The destinations on {@code page}, in order - possibly fewer than a full page, never more. */
    public static List<NavigationTarget> slice(final List<NavigationTarget> targets, final int page) {
        final int from = Math.min(page * NavigatePanel.ENTRIES_PER_PAGE, targets.size());
        final int to = Math.min(from + NavigatePanel.ENTRIES_PER_PAGE, targets.size());
        return List.copyOf(targets.subList(from, to));
    }

    /**
     * What a destination is called on its row.
     *
     * <p>A POI's name is the player's own text and is used as typed; the two built-in kinds carry a
     * message key instead. {@link MenuFont} folds either onto the sheet's alphabet later.</p>
     */
    public static String label(final NavigationTarget target, final Messages messages,
                               final Locale locale) {
        return target.kind() == NavigationTarget.Kind.POI
                ? target.label()
                : messages.get(locale, target.label());
    }

    /**
     * What stands at the right edge of a row.
     *
     * <p>Blocks when the destination is in the world the player is standing in, and the words for
     * "another world" when it is not - because a number there would be a straight-line distance
     * through a dimension the player is not in, which is worse than no number at all. The distance
     * is three-dimensional and rounded, and it is <b>computed when the menu opens</b>: it is a
     * caption on a list, not a live readout, and the HUD is what tracks the one destination that is
     * being walked to.</p>
     */
    public static String distance(final NavigationTarget target, final String world,
                                  final double x, final double y, final double z,
                                  final Messages messages, final Locale locale) {
        if (!target.isIn(world)) {
            return messages.get(locale, "smp.navigate.other-world");
        }
        final double dx = target.x() - x;
        final double dy = target.y() - y;
        final double dz = target.z() - z;
        return messages.format(locale, "smp.navigate.distance",
                "blocks", Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)));
    }

    /** Every drawn row of one page, in order. */
    public static List<NavigatePanel.Entry> entries(final List<NavigationTarget> targets,
                                                    final int page, final String world,
                                                    final double x, final double y, final double z,
                                                    final Optional<NavigationTarget> active,
                                                    final Messages messages, final Locale locale) {
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
    public static String pageLabel(final int page, final int total, final Messages messages,
                                   final Locale locale) {
        return messages.format(locale, "smp.navigate.page",
                "page", page + 1, "pages", pages(total));
    }
}
