package eu.nordtal.season.discordbot.registration;

import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.StoredLayouts;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.dv8tion.jda.api.JDA;
import org.jdbi.v3.core.Jdbi;

/** Posts and edits a game's Register message, one per configured language, each a {@link ManagedMessage}. */
public final class RegisterMessages {

    /** The Register message: the team, the name and the no-show rule, the Register button under them. */
    static final Layout REGISTER = Layout.discord(
            "access",
            "hunger-games-register",
            "Register",
            List.of(Element.button("register", "access/register.button")),
            Part.container(
                    Tone.BRAND,
                    Part.text(Line.title("access/register.title")),
                    Part.text(Line.heading("access/register.team-heading"), Line.text("access/register.team")),
                    Part.text(Line.heading("access/register.name-heading"), Line.text("access/register.name")),
                    Part.text(Line.heading("access/register.partner-heading"), Line.text("access/register.partner"))),
            Part.row("register"));

    private final GuildLanguages languages;
    private final LayoutRenderer renderer;
    private final ManagedMessage managed;

    public RegisterMessages(
            final JDA jda,
            final GuildLanguages languages,
            final DiscordRenderer messages,
            final StoredLayouts layouts,
            final Jdbi jdbi) {
        this.languages = languages;
        this.renderer = new LayoutRenderer(messages, layouts);
        this.managed = new ManagedMessage(jda, jdbi);
    }

    /** Posts or edits the Register message in every configured language's channel. */
    public void publishAll() {
        for (final GuildLanguages.Language language : languages.all()) {
            managed.publish(
                    language.hungerGamesRegisterKind(),
                    language.hungerGamesChannelId(),
                    register(renderer, language.locale()));
        }
    }

    static LayoutRenderer.Drawn register(final LayoutRenderer renderer, final Locale locale) {
        return renderer.render(
                REGISTER,
                new LayoutRenderer.Filling(
                        Map.of(),
                        Map.of(
                                "register",
                                new LayoutRenderer.FilledButton(
                                        Ids.of(Game.HUNGER_GAMES).register()))),
                locale);
    }
}
