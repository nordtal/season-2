package eu.nordtal.s2.smp.milestone;

import java.util.Optional;

/**
 * The three ways an objective's progress is measured (docs/smp.md#objective-types).
 *
 * <p>A closed set by design, and {@code V6__smp.sql} CHECK-constrains
 * {@code smp_objective.type} to exactly these three - unlike {@code smp_aura_event.reason}, which
 * is left open. The difference is deliberate: each type here is a different way of <em>measuring</em>
 * a contribution rather than a different piece of content, so a fourth is a design change that
 * should cost a migration, while a new {@code HAND_IN} objective is a config edit and costs nothing.
 */
public enum ObjectiveType {

    /**
     * Items delivered at the spawn NPC. An individual's share is the amount they delivered.
     *
     * <p>Deliberately includes farmable materials in rising quantities from M3 onward: farms can
     * only be built in Nordtal, and building the farm is meant to <em>be</em> the content of the
     * late game. Nothing is hopper-fed - automated delivery would turn contribution counting into a
     * race between farms.
     */
    HAND_IN,

    /**
     * A vanilla statistic summed across all players; an individual's share is their own increase
     * since the objective started.
     *
     * <p><b>Active statistics only</b> - blocks mined, mobs killed, items crafted, trades made.
     * Never distance walked, time played or damage taken: a passive statistic would hand every
     * player a contribution share simply for being online, which is the free-riding the payout
     * rules exist to prevent. Nothing in the code can enforce that; it is a rule about content and
     * it is why every objective also carries a {@code role}.
     */
    STATISTIC,

    /**
     * How many <em>distinct</em> players earned a given advancement. An individual's share is 1 or 0.
     *
     * <p>Every milestone carries exactly one of these as its participation gate, because it is the
     * only type three industrious people cannot finish alone - and the only one whose progress
     * survives churn, since a player who earned the advancement and never logs in again stays
     * counted.
     */
    ADVANCEMENT;

    /**
     * @param name a value from the config or from {@code smp_objective.type}
     * @return the type, or empty for anything else - never an exception, because this parses both
     *         a hand-edited file and a column an older version wrote
     */
    public static Optional<ObjectiveType> parse(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (final ObjectiveType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
