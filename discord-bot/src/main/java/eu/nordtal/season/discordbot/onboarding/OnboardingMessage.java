package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The managed messages of the choose channel, with a country select, and of the change channel, both in English. */
final class OnboardingMessage {
    static final String KIND = "ONBOARDING";
    static final String CHANGE_KIND = "ONBOARDING_CHANGE";
    static final DocumentSpec<DocumentSpec.None> WELCOME = DocumentSpec.message(
                    StoredDocuments.NAMESPACE, "onboarding-welcome", "Onboarding", Slot.select("countries"))
            .titled("Welcome and country choice")
            .inEnglishOnly();
    static final DocumentSpec<DocumentSpec.None> CHANGE = DocumentSpec.message(
                    StoredDocuments.NAMESPACE, "onboarding-change", "Onboarding", Slot.button("settings"))
            .titled("Change of country and language")
            .inEnglishOnly();
    private final DocumentRenderer renderer;

    OnboardingMessage(final DiscordRenderer messages, final StoredDocuments documents) {
        this.renderer = new DocumentRenderer(messages, documents);
    }

    void publish(final ManagedMessage managed, final String choose, final String change, final Choices choices) {
        managed.publish(KIND, choose, welcome(choices));
        managed.publish(CHANGE_KIND, change, change());
    }

    DocumentRenderer.Drawn welcome(final Choices choices) {
        return renderer.render(
                WELCOME,
                new DocumentSpec.None(),
                Map.of("countries", countries(choices, Ids.ONBOARD_COUNTRY, null)),
                Locale.ENGLISH);
    }

    DocumentRenderer.Drawn change() {
        return renderer.render(
                CHANGE,
                new DocumentSpec.None(),
                Map.of("settings", new DocumentRenderer.FilledButton(Ids.ONBOARD_SETTINGS)),
                Locale.ENGLISH);
    }

    static DocumentRenderer.FilledSelect countries(
            final Choices choices, final String id, final @Nullable String selected) {
        final List<DocumentRenderer.Option> options = choices.of(Choices.Kind.COUNTRY).stream()
                .map(country -> new DocumentRenderer.Option(country.value(), country.name(), Map.of(), country.emoji()))
                .toList();
        return new DocumentRenderer.FilledSelect(id, options, selected);
    }

    /** Returns the select of the languages, each by its own name and emoji. */
    static DocumentRenderer.FilledSelect languages(
            final GuildLanguages languages, final String id, final @Nullable String selected) {
        final List<DocumentRenderer.Option> options = languages.all().stream()
                .map(language -> new DocumentRenderer.Option(
                        Locales.tag(language.locale()), language.ownName(), Map.of(), emoji(language)))
                .toList();
        return new DocumentRenderer.FilledSelect(id, options, selected);
    }

    private static @Nullable String emoji(final GuildLanguages.Language language) {
        return language.emoji().isBlank() ? null : language.emoji();
    }
}
