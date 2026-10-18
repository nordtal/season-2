package eu.nordtal.season.smp.board;

import java.util.Optional;

public enum BoardKind {
    OBJECTIVE,

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
}
