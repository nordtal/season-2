package eu.nordtal.s2.internalapi.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One message key in both languages, packaged and overridden, kept apart so an override shows as one.
 *
 * @param key the dotted key, for example {@code contribution.title}
 * @param english the English text the jar ships, or {@code null} for a key only an override names
 * @param german the German text the jar ships, or {@code null} when untranslated (English is used)
 * @param overrideEnglish the operator's English override, or {@code null}
 * @param overrideGerman the operator's German override, or {@code null}
 * @param inBundle whether the jar declares this key; {@code false} for a key only an override names
 * @param name the name an admin reads, from the schema; {@code null} when the schema does not describe the key
 * @param description a sentence for a hard case, or {@code null}
 * @param args the placeholders in parameter order; empty when the schema does not describe the key
 * @param section the names of the sections around the key, outermost first; {@code null} for one without a name
 * @param format {@code MINIMESSAGE}, {@code DISCORD_MARKDOWN} or {@code PLAIN}; {@code null} when the schema is silent
 * @param shown where the text is shown, for example {@code TITLE}, or {@code null} when the schema does not say
 */
public record MessageEntry(
        String key,
        @Nullable String english,
        @Nullable String german,
        @Nullable String overrideEnglish,
        @Nullable String overrideGerman,
        boolean inBundle,
        @Nullable String name,
        @Nullable String description,
        List<MessageArg> args,
        List<String> section,
        @Nullable String format,
        @Nullable String shown) {

    public MessageEntry {
        args = List.copyOf(args);
        // A nameless section is null here, which List.copyOf would refuse.
        section = Collections.unmodifiableList(new ArrayList<>(section));
    }

    /** Whether the jar's schema describes this key, which is what makes its placeholders checkable. */
    public boolean described() {
        return name != null;
    }
}
