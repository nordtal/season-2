package eu.nordtal.season.discordbot.onboarding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The changes to the language, region and lock roles of one member and to their record.
 *
 * @param add the ids of the roles to give
 * @param remove the ids of the roles to take
 * @param record the changes of the record, one per kind whose value differs
 */
record Settlement(Set<String> add, Set<String> remove, List<Change> record) {

    /**
     * A new value of the record.
     *
     * @param value the tag or zone, or {@code null} for the network's value
     */
    record Change(Choices.Kind kind, @Nullable String value) {}

    /**
     * Decides what a member keeps.
     * Per kind the member keeps the chosen role, else the recorded one, else the first held one.
     *
     * @param idOf the id of the role with a key, if the guild has it. A kind with a missing role is not changed.
     * @param held the ids of the roles the member holds
     * @param chosen the ids of the roles just given or chosen, which win over the other roles of their kind
     * @param lock the id of the lock role, or {@code null} if there is none
     * @param locking whether a member without a language or a region gets the lock role. A member who holds the
     *     role keeps it until the member holds both, whether the lock is on or off.
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
            if (missing && (locking || held.contains(lock))) {
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

    boolean isEmpty() {
        return add.isEmpty() && remove.isEmpty() && record.isEmpty();
    }
}
