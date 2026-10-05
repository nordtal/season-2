package eu.nordtal.s2.discordbot.onboarding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What one member's language, region and lock roles and their record become, decided apart from Discord.
 *
 * @param add the ids of the roles to give
 * @param remove the ids of the roles to take
 * @param record what changes in the record, one entry per kind whose value differs
 */
record Settlement(Set<String> add, Set<String> remove, List<Change> record) {

    /**
     * One value the record takes.
     *
     * @param value the tag or zone, or {@code null} for the network's
     */
    record Change(Choices.Kind kind, @Nullable String value) {}

    /**
     * Decides what a member keeps: per kind the newest chosen role, else the one the record holds, else the first.
     *
     * @param idOf the id of a key's role while the guild has it; a kind with a role missing is left alone
     * @param held the ids of the roles the member holds
     * @param chosen the ids just given or chosen, which win over the rest of their kind, held or not
     * @param lock the lock role's id, or {@code null} while there is none
     * @param locking whether a member without a language or a region holds the lock role
     */
    static Settlement of(
            final Choices choices,
            final Function<String, Optional<String>> idOf,
            final Set<String> held,
            final Set<String> chosen,
            final Records.Recorded recorded,
            final @Nullable String lock,
            final boolean locking) {
        final Set<String> holding = new HashSet<>(held);
        holding.addAll(chosen);
        final Set<String> target = new HashSet<>();
        final Set<String> managed = new HashSet<>();
        final List<Change> changes = new ArrayList<>();
        boolean missing = false;
        for (final Choices.Kind kind : Choices.Kind.values()) {
            if (!choices.ready(kind, idOf)) {
                continue;
            }
            choices.of(kind).forEach(choice -> idOf.apply(choice.key()).ifPresent(managed::add));
            final Optional<Choices.Choice> kept = Choices.kept(
                    choices.among(kind, holding, idOf), choices.among(kind, chosen, idOf), recorded.of(kind));
            kept.flatMap(choice -> idOf.apply(choice.key())).ifPresent(target::add);
            missing |= kept.isEmpty();
            final String value = kept.map(Choices.Choice::value).orElse(null);
            if (!Objects.equals(value, recorded.of(kind))) {
                changes.add(new Change(kind, value));
            }
        }
        if (lock != null) {
            managed.add(lock);
            if (locking && missing) {
                target.add(lock);
            }
        }
        final Set<String> add = new HashSet<>(target);
        add.removeAll(held);
        final Set<String> remove = new HashSet<>(managed);
        remove.retainAll(held);
        remove.removeAll(target);
        return new Settlement(Set.copyOf(add), Set.copyOf(remove), List.copyOf(changes));
    }

    /** Returns whether nothing changes, in Discord or in the record. */
    boolean isEmpty() {
        return add.isEmpty() && remove.isEmpty() && record.isEmpty();
    }
}
