package eu.nordtal.s2.smp.board;

import java.util.Locale;
import java.util.Optional;

/** Which of the two boards at the spawn an anchor is. */
public enum BoardKind {

    /** The current milestone and each of its objectives, with progress. */
    OBJECTIVE,

    /** Who has the most aura. */
    AURA;

    public static Optional<BoardKind> parse(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (final BoardKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name.trim())) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    public String messageKey() {
        return "smp.board." + name().toLowerCase(Locale.ROOT) + ".title";
    }
}
