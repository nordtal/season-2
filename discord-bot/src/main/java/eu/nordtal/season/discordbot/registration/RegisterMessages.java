package eu.nordtal.season.discordbot.registration;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.discordbot.Card;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.config.Languages;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.jdbi.v3.core.Jdbi;

/** Posts and edits a game's Register message, one per configured language, each a {@link ManagedMessage}. */
public final class RegisterMessages {

    private final Languages languages;
    private final DiscordRenderer messages;
    private final ManagedMessage managed;
    private final Ids ids = Ids.of(Game.HUNGER_GAMES);

    public RegisterMessages(final JDA jda, final Languages languages, final DiscordRenderer messages, final Jdbi jdbi) {
        this.languages = languages;
        this.messages = messages;
        this.managed = new ManagedMessage(jda, jdbi);
    }

    /** Posts or edits the Register message in every configured language's channel. */
    public void publishAll() {
        for (final Languages.Language language : languages.all()) {
            publish(language.hungerGamesRegisterKind(), language.hungerGamesChannelId(), language.locale());
        }
    }

    private void publish(final String kind, final String channelId, final Locale locale) {
        final List<ActionRow> components = List.of(ActionRow.of(Button.primary(
                ids.register(), messages.format(locale, MESSAGES.register().button()))));
        managed.publish(kind, channelId, new ManagedMessage.Content(List.of(registerEmbed(locale)), components, null));
    }

    private MessageEmbed registerEmbed(final Locale locale) {
        return Card.of(messages.format(locale, MESSAGES.register().title()))
                .field(
                        messages.format(locale, MESSAGES.register().teamHeading()),
                        messages.format(locale, MESSAGES.register().team()))
                .field(
                        messages.format(locale, MESSAGES.register().nameHeading()),
                        messages.format(locale, MESSAGES.register().name()))
                .field(
                        messages.format(locale, MESSAGES.register().partnerHeading()),
                        messages.format(locale, MESSAGES.register().partner()))
                .build();
    }
}
