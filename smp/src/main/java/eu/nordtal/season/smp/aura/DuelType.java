package eu.nordtal.season.smp.aura;

import java.util.Optional;

/** The platform a duel starts on. It decides the loadout of both fighters. */
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
