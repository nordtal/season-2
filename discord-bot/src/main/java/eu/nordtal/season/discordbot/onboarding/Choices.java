package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.config.OnboardingSpec;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The roles a member chooses from, a language and a country, in the order they are offered.
 *
 * The rules about which role a member keeps are here and use no Discord object.
 */
final class Choices {

    enum Kind {
        LANGUAGE,
        COUNTRY
    }

    /**
     * One role a member may choose.
     *
     * @param value the language tag or the country key that the record stores
     */
    record Choice(Kind kind, String key, String name, String value, String zone, String language, String emoji) {
        Choice(final Kind kind, final String key, final String name, final String value) {
            this(kind, key, name, value, "", "", "");
        }
    }

    private final List<Choice> languages;
    private final List<Choice> countries;
    private final String lockName;

    Choices(final List<Choice> languages, final List<Choice> countries, final String lockName) {
        this.languages = List.copyOf(languages);
        this.countries = List.copyOf(countries);
        this.lockName = lockName;
    }

    static Choices of(final GuildLanguages languages, final OnboardingSpec onboarding) {
        final List<Choice> spoken = languages.all().stream()
                .map(language -> new Choice(
                        Kind.LANGUAGE,
                        GuildRoles.language(language.tag()),
                        language.roleName(),
                        Locales.tag(language.locale())))
                .toList();
        final List<Choice> countries = onboarding.countries().stream()
                .map(country -> new Choice(
                        Kind.COUNTRY,
                        GuildRoles.country(country.key()),
                        country.name().strip(),
                        country.key(),
                        country.zone(),
                        country.language(),
                        country.emoji()))
                .toList();
        return new Choices(spoken, countries, onboarding.lockRole().strip());
    }

    List<Choice> of(final Kind kind) {
        return kind == Kind.LANGUAGE ? languages : countries;
    }

    /** Returns every role of the choices, then the lock role. */
    List<GuildRoles.Wanted> wanted() {
        final List<GuildRoles.Wanted> wanted = new ArrayList<>();
        for (final Kind kind : Kind.values()) {
            of(kind).forEach(choice -> wanted.add(new GuildRoles.Wanted(choice.key(), choice.name())));
        }
        wanted.add(new GuildRoles.Wanted(GuildRoles.LOCK, lockName));
        return wanted;
    }

    /**
     * Returns whether the kind has choices and the guild has every role of the kind.
     *
     * Settling the kind needs this, so a member keeps the record for a role that the bot could not take.
     */
    boolean ready(final Kind kind, final Function<String, Optional<String>> idOf) {
        return !of(kind).isEmpty()
                && of(kind).stream().allMatch(choice -> idOf.apply(choice.key()).isPresent());
    }

    /** Returns the choices of the kind whose role is in {@code roleIds}, in the order they are offered. */
    List<Choice> among(
            final Kind kind, final Collection<String> roleIds, final Function<String, Optional<String>> idOf) {
        return of(kind).stream()
                .filter(choice ->
                        idOf.apply(choice.key()).filter(roleIds::contains).isPresent())
                .toList();
    }

    Optional<Choice> byValue(final Kind kind, final @Nullable String value) {
        return of(kind).stream().filter(choice -> choice.value().equals(value)).findFirst();
    }

    /**
     * Returns the choice a member keeps: the first added one, else the recorded one, else the first held one.
     *
     * @param held the choices of one kind that the member holds, in the order offered
     * @param added the held choices that were just added, in the order offered
     * @param recorded the value in the member's record, or {@code null}
     */
    static Optional<Choice> kept(final List<Choice> held, final List<Choice> added, final @Nullable String recorded) {
        if (!added.isEmpty()) {
            return Optional.of(added.getFirst());
        }
        return held.stream()
                .filter(choice -> Objects.equals(choice.value(), recorded))
                .findFirst()
                .or(() -> held.stream().findFirst());
    }
}
