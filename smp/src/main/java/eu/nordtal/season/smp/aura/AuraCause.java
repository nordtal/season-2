package eu.nordtal.season.smp.aura;

import eu.nordtal.season.common.id.Actor;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public sealed interface AuraCause {

    AuraReason reason();

    /** @param kit the name of the kit both fighters agreed on */
    record Duel(boolean won, String kit) implements AuraCause {
        public Duel {
            Objects.requireNonNull(kit, "kit");
        }

        @Override
        public AuraReason reason() {
            return won ? AuraReason.DUEL_WIN : AuraReason.DUEL_LOSS;
        }
    }

    /**
     * A death outside the duel arena.
     *
     * @param reason     {@link AuraReason#DEATH} or {@link AuraReason#DEATH_LISTED}
     * @param damageType the namespaced damage type, or null when the server names none
     */
    record Death(AuraReason reason, @Nullable String damageType) implements AuraCause {
        public Death {
            if (reason != AuraReason.DEATH && reason != AuraReason.DEATH_LISTED) {
                throw new IllegalArgumentException("a death costs DEATH or DEATH_LISTED, not " + reason);
            }
        }
    }

    record Advancement(String key) implements AuraCause {
        public Advancement {
            Objects.requireNonNull(key, "key");
        }

        @Override
        public AuraReason reason() {
            return AuraReason.ADVANCEMENT;
        }
    }

    record Contribution(UUID objectiveId) implements AuraCause {
        public Contribution {
            Objects.requireNonNull(objectiveId, "objectiveId");
        }

        @Override
        public AuraReason reason() {
            return AuraReason.CONTRIBUTION;
        }
    }

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
