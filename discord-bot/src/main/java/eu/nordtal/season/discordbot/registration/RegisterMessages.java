package eu.nordtal.season.discordbot.registration;

import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import java.util.Locale;
import java.util.Map;
import net.dv8tion.jda.api.JDA;
import org.jdbi.v3.core.Jdbi;

/** Posts and edits the Register message of a game, one {@link ManagedMessage} per configured language. */
public final class RegisterMessages {

    static final DocumentSpec<DocumentSpec.None> REGISTER = DocumentSpec.message(
            StoredDocuments.NAMESPACE, "hunger-games-register", "Register", Slot.button("register"));

    private final GuildLanguages languages;
    private final DocumentRenderer renderer;
    private final ManagedMessage managed;

    public RegisterMessages(
            final JDA jda,
            final GuildLanguages languages,
            final DiscordRenderer messages,
            final StoredDocuments documents,
            final Jdbi jdbi) {
        this.languages = languages;
        this.renderer = new DocumentRenderer(messages, documents);
        this.managed = new ManagedMessage(jda, jdbi);
    }

    /** Posts or edits the Register message in the channel of every configured language. */
    public void publishAll() {
        for (final GuildLanguages.Language language : languages.all()) {
            managed.publish(
                    language.hungerGamesRegisterKind(),
                    language.hungerGamesChannelId(),
                    register(renderer, language.locale()));
        }
    }

    static DocumentRenderer.Drawn register(final DocumentRenderer renderer, final Locale locale) {
        return renderer.render(
                REGISTER,
                new DocumentSpec.None(),
                Map.of(
                        "register",
                        new DocumentRenderer.FilledButton(
                                Ids.of(Game.HUNGER_GAMES).register())),
                locale);
    }
}
