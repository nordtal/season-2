package eu.nordtal.season.packrendering.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.jspecify.annotations.Nullable;

/** Cuts the name a container shows to its room. */
final class ContainerNames {

    private ContainerNames() {}

    /**
     * Returns {@code name} cut with an ellipsis past {@code room} when all of it is literal, in its first style.
     * A translated name is the client's to resolve and stays as it is.
     */
    static Component cut(final Component name, final int room) {
        final String plain = plain(name);
        if (plain == null || Letters.visibleWidth(plain) <= room) {
            return name;
        }
        return Component.text(Letters.cut(plain, room), name.style());
    }

    /** The text of {@code name} when every part of it is literal, or null. */
    static @Nullable String plain(final Component name) {
        if (!(name instanceof final TextComponent text)) {
            return null;
        }
        final StringBuilder out = new StringBuilder(text.content());
        for (final Component child : name.children()) {
            final String part = plain(child);
            if (part == null) {
                return null;
            }
            out.append(part);
        }
        return out.toString();
    }
}
