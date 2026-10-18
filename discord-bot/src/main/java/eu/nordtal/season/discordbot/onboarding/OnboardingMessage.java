package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import java.util.List;
import java.util.Map;

/**
 * The two onboarding messages: the welcome in the onboarding channel and the change message in the change channel.
 *
 * Each is a {@link ManagedMessage}.
 */
final class OnboardingMessage {

    static final String KIND = "ONBOARDING";

    static final String CHANGE_KIND = "ONBOARDING_CHANGE";

    /** The welcome in the default language with a banner and a select for the language. */
    static final DocumentSpec<DocumentSpec.None> WELCOME = DocumentSpec.message(
            StoredDocuments.NAMESPACE, "onboarding-welcome", "Onboarding", Slot.select("language"));

    /** The change message with one box and one button per language. The button opens the dialog in that language. */
    static final DocumentSpec<DocumentSpec.None> CHANGE = DocumentSpec.message(
            StoredDocuments.NAMESPACE,
            "onboarding-change",
            "Onboarding",
            Slot.languages("each"),
            Slot.button("change"));

    private final GuildLanguages languages;
    private final DocumentRenderer renderer;

    OnboardingMessage(final GuildLanguages languages, final DiscordRenderer messages, final StoredDocuments documents) {
        this.languages = languages;
        this.renderer = new DocumentRenderer(messages, documents);
    }

    /** Posts or edits both messages. An empty channel id means the message is not posted. */
    void publish(final ManagedMessage managed, final String onboardingChannelId, final String changeChannelId) {
        managed.publish(KIND, onboardingChannelId, welcome());
        managed.publish(CHANGE_KIND, changeChannelId, change());
    }

    DocumentRenderer.Drawn welcome() {
        final List<DocumentRenderer.Option> options = languages.all().stream()
                .map(language -> new DocumentRenderer.Option(
                        language.tag(),
                        language.ownName(),
                        Map.of(),
                        Locales.flagCountry(language.locale()).map(Mark::flag).orElse(null)))
                .toList();
        return renderer.render(
                WELCOME,
                new DocumentSpec.None(),
                Map.of("language", new DocumentRenderer.FilledSelect(Ids.ONBOARD_PICK_LANGUAGE, options)),
                languages.fallback().locale());
    }

    DocumentRenderer.Drawn change() {
        return renderer.render(
                CHANGE,
                new DocumentSpec.None(),
                Map.of(
                        "each",
                        new DocumentRenderer.FilledLanguages(languages.all().stream()
                                .map(GuildLanguages.Language::locale)
                                .toList()),
                        "change",
                        new DocumentRenderer.FilledButton(Ids.ONBOARD)),
                languages.fallback().locale());
    }
}
