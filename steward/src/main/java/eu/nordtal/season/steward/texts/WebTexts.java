package eu.nordtal.season.steward.texts;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.AdminTexts;
import eu.nordtal.season.messages.CheckMessages;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.spec.MessageSchema;
import io.javalin.http.Context;
import java.util.List;

/** Serves the texts of Steward's page with their overrides to the browser as parsed trees, in one language. */
public final class WebTexts {

    /** The bundles of the page: the Steward texts, the admin texts and the validator messages. */
    public static final List<Class<?>> SPECS = List.of(StewardTexts.class, AdminTexts.class, CheckMessages.class);

    private final Messages messages;

    private WebTexts(final Messages messages) {
        this.messages = messages;
    }

    public static WebTexts load() {
        return new WebTexts(load(WebTexts.class.getClassLoader()));
    }

    /** Loads the bundles from {@code loader}. */
    public static Messages load(final ClassLoader loader) {
        return Messages.load(
                loader,
                SPECS.stream()
                        .map(spec -> "messages/" + MessageSchema.bundle(spec))
                        .toList(),
                Locales.DEFAULT);
    }

    public Messages messages() {
        return messages;
    }

    /** {@code GET /api/texts} answers the variants of every key. */
    public void serve(final Context ctx) {
        ctx.json(MessageJson.texts(messages, Locales.DEFAULT));
    }
}
