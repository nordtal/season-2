package eu.nordtal.season.discordbot.onboarding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** The role and record changes that keep one country, one language and the lock consistent. */
record Settlement(Set<String> add, Set<String> remove, List<Change> record) {
    record Change(
            Choices.Kind kind,
            @Nullable String value,
            @Nullable String zone) {
        Change(final Choices.Kind kind, final @Nullable String value) {
            this(kind, value, null);
        }
    }

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
        final Optional<Choices.Choice> country =
                kept(choices, Choices.Kind.COUNTRY, idOf, holding, chosen, recorded.country());
        if (choices.ready(Choices.Kind.COUNTRY, idOf)) {
            manage(choices, Choices.Kind.COUNTRY, idOf, managed);
            country.flatMap(choice -> idOf.apply(choice.key())).ifPresent(target::add);
            final String value = country.map(Choices.Choice::value).orElse(null);
            final String zone = country.map(Choices.Choice::zone)
                    .filter(valueZone -> !valueZone.isEmpty())
                    .orElse(null);
            if (!Objects.equals(value, recorded.country()) || !Objects.equals(zone, recorded.zone())) {
                changes.add(new Change(Choices.Kind.COUNTRY, value, zone));
            }
        }
        if (choices.ready(Choices.Kind.LANGUAGE, idOf)) {
            manage(choices, Choices.Kind.LANGUAGE, idOf, managed);
            final Optional<Choices.Choice> language = kept(
                            choices, Choices.Kind.LANGUAGE, idOf, holding, chosen, recorded.language())
                    .or(() -> country.flatMap(choice -> choices.byValue(Choices.Kind.LANGUAGE, choice.language())));
            language.flatMap(choice -> idOf.apply(choice.key())).ifPresent(target::add);
            final String tag = language.map(Choices.Choice::value).orElse(null);
            if (!Objects.equals(tag, recorded.language())) {
                changes.add(new Change(Choices.Kind.LANGUAGE, tag));
            }
        }
        if (lock != null) {
            managed.add(lock);
            if (locking && country.isEmpty()) {
                target.add(lock);
            }
        }
        final Set<String> add = new HashSet<>(target);
        add.removeAll(held);
        managed.retainAll(held);
        managed.removeAll(target);
        return new Settlement(Set.copyOf(add), Set.copyOf(managed), List.copyOf(changes));
    }

    private static Optional<Choices.Choice> kept(
            final Choices choices,
            final Choices.Kind kind,
            final Function<String, Optional<String>> idOf,
            final Set<String> held,
            final Set<String> chosen,
            final @Nullable String recorded) {
        return Choices.kept(choices.among(kind, held, idOf), choices.among(kind, chosen, idOf), recorded);
    }

    private static void manage(
            final Choices choices,
            final Choices.Kind kind,
            final Function<String, Optional<String>> idOf,
            final Set<String> managed) {
        choices.of(kind).forEach(choice -> idOf.apply(choice.key()).ifPresent(managed::add));
    }

    boolean isEmpty() {
        return add.isEmpty() && remove.isEmpty() && record.isEmpty();
    }
}
