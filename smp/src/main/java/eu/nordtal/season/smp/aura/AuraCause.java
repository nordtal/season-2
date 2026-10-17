package eu.nordtal.season.smp.aura;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.smp.duel.DuelType;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Why aura changed, as the typed values that explain a leaderboard position: its reason and a row of its own. */
public sealed interface AuraCause {

    /** Returns what goes into {@code smp_aura_event.reason}. */
    AuraReason reason();

    /** A duel's stake, won or lost, on the platform it was fought on. */
    record Duel(boolean won, DuelType type) implements AuraCause {
        public Duel {
            Objects.requireNonNull(type, "type");
        }

        @Override
        public AuraReason reason() {
            return won ? AuraReason.DUEL_WIN : AuraReason.DUEL_LOSS;
        }
    }

    /**
     * A death away from the arena.
     *
     * @param reason     {@link AuraReason#DEATH} or {@link AuraReason#DEATH_LISTED}
     * @param damageType the namespaced damage type, null when the server named none
     */
    record Death(AuraReason reason, @Nullable String damageType) implements AuraCause {
        public Death {
            if (reason != AuraReason.DEATH && reason != AuraReason.DEATH_LISTED) {
                throw new IllegalArgumentException("a death costs DEATH or DEATH_LISTED, not " + reason);
            }
        }
    }

    /** One of the curated advancements, by its namespaced key. */
    record Advancement(String key) implements AuraCause {
        public Advancement {
            Objects.requireNonNull(key, "key");
        }

        @Override
        public AuraReason reason() {
            return AuraReason.ADVANCEMENT;
        }
    }

    /** A share of the aura budget of the objective with this id. */
    record Contribution(UUID objectiveId) implements AuraCause {
        public Contribution {
            Objects.requireNonNull(objectiveId, "objectiveId");
        }

        @Override
        public AuraReason reason() {
            return AuraReason.CONTRIBUTION;
        }
    }

    /** A change an admin booked by hand. */
    record Admin(Actor actor) implements AuraCause {
        public Admin {
            Objects.requireNonNull(actor, "actor");
        }

        @Override
        public AuraReason reason() {
            return AuraReason.ADMIN;
        }
    }
}
