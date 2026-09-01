package eu.nordtal.s2.smp.duel;

import java.util.Optional;

/** Which of the two platforms a duel started on, and therefore which loadout both fighters get. */
public enum DuelType {

    SWORD,
    BOW;

    public static Optional<DuelType> parse(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (final DuelType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
