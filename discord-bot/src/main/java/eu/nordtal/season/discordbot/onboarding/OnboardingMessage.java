package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The start channel's managed message with one text per language and a country select. */
final class OnboardingMessage {
    static final String KIND = "ONBOARDING";
    static final DocumentSpec<DocumentSpec.None> WELCOME = DocumentSpec.message(
                    StoredDocuments.NAMESPACE,
                    "onboarding-welcome",
                    "Onboarding",
                    Slot.languages("each"),
                    Slot.select("countries"),
                    Slot.button("settings"))
            .titled("Welcome and country choice");
    private final GuildLanguages languages;
    private final DocumentRenderer renderer;

    OnboardingMessage(final GuildLanguages languages, final DiscordRenderer messages, final StoredDocuments documents) {
        this.languages = languages;
        this.renderer = new DocumentRenderer(messages, documents);
    }

    void publish(final ManagedMessage managed, final String channel, final Choices choices) {
        managed.publish(KIND, channel, welcome(choices));
    }

    DocumentRenderer.Drawn welcome(final Choices choices) {
        return renderer.render(
                WELCOME,
                new DocumentSpec.None(),
                Map.of(
                        "each",
                                new DocumentRenderer.FilledLanguages(languages.all().stream()
                                        .map(GuildLanguages.Language::locale)
                                        .toList()),
                        "countries", countries(choices, null),
                        "settings", new DocumentRenderer.FilledButton(Ids.ONBOARD_SETTINGS)),
                languages.fallback().locale());
    }

    static DocumentRenderer.FilledSelect countries(final Choices choices, final @Nullable String selected) {
        final List<DocumentRenderer.Option> options = choices.of(Choices.Kind.COUNTRY).stream()
                .map(country -> new DocumentRenderer.Option(country.value(), country.name(), Map.of(), country.emoji()))
                .toList();
        return new DocumentRenderer.FilledSelect(Ids.ONBOARD_COUNTRY, options, selected);
    }
}
