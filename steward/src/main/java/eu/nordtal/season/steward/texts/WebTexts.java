package eu.nordtal.season.steward.texts;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.AdminTexts;
import eu.nordtal.season.messages.CheckMessages;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.spec.MessageSchema;
import io.javalin.http.Context;
import java.util.List;

/**
 * The texts Steward's page shows, overrides layered, handed to the browser's web target as parsed trees.
 * Steward is English, so there is one language; the browser formats each value in its own zone.
 */
public final class WebTexts {

    /** The bundles the page renders over {@code values}: its own, the admin texts, and the validator's problems. */
    public static final List<Class<?>> SPECS = List.of(StewardTexts.class, AdminTexts.class, CheckMessages.class);

    private final Messages messages;

    private WebTexts(final Messages messages) {
        this.messages = messages;
    }

    /** Loads the bundles from Steward's own jar. */
    public static WebTexts load() {
        return new WebTexts(load(WebTexts.class.getClassLoader()));
    }

    /** Loads the bundles from {@code loader}, for the build that writes the frontend's packaged copy. */
    public static Messages load(final ClassLoader loader) {
        return Messages.load(
                loader,
                SPECS.stream()
                        .map(spec -> "messages/" + MessageSchema.bundle(spec))
                        .toList(),
                Locales.DEFAULT);
    }

    /** Returns the bundles the overrides are layered on. */
    public Messages messages() {
        return messages;
    }

    /** {@code GET /api/texts}: every key's variants as the page renders them now. */
    public void serve(final Context ctx) {
        ctx.json(MessageJson.texts(messages, Locales.DEFAULT));
    }
}
