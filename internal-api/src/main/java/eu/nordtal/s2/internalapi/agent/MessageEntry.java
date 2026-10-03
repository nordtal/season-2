package eu.nordtal.s2.internalapi.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One message key in both languages, packaged and overridden, kept apart so an override shows as one.
 *
 * @param key the dotted key, for example {@code contribution.title}
 * @param bundle the packaged bundle the key belongs to, the directory under {@code messages/} in the jar
 * @param english the first English text the jar ships, or {@code null} for a key only an override names
 * @param german the first German text the jar ships, or {@code null} when untranslated (English is used)
 * @param englishTexts every English text the jar ships for the key, its variants in order, which an override keeps
 * @param germanTexts the same for German, empty when untranslated
 * @param overrideEnglish the admin's English override, which steward fills in from the database, or {@code null}
 * @param overrideGerman the admin's German override, likewise, or {@code null}
 * @param inBundle whether the jar declares this key
 * @param name the name an admin reads, from the schema; {@code null} when the schema does not describe the key
 * @param description a sentence for a hard case, or {@code null}
 * @param args the placeholders in parameter order; empty when the schema does not describe the key
 * @param section the names of the sections around the key, outermost first; {@code null} for one without a name
 * @param format {@code MINIMESSAGE}, {@code DISCORD_MARKDOWN} or {@code PLAIN}; {@code null} when the schema is silent
 * @param shown where the text is shown, for example {@code TITLE}, or {@code null} when the schema does not say
 */
public record MessageEntry(
        String key,
        String bundle,
        @Nullable String english,
        @Nullable String german,
        List<String> englishTexts,
        List<String> germanTexts,
        @Nullable String overrideEnglish,
        @Nullable String overrideGerman,
        boolean inBundle,
        @Nullable String name,
        @Nullable String description,
        List<MessageArg> args,
        List<@Nullable String> section,
        @Nullable String format,
        @Nullable String shown) {

    public MessageEntry {
        englishTexts = List.copyOf(englishTexts);
        germanTexts = List.copyOf(germanTexts);
        args = List.copyOf(args);
        // A nameless section is null here, which List.copyOf would refuse.
        section = Collections.unmodifiableList(new ArrayList<>(section));
    }

    /** The same key with the admin's overrides, {@code null} for a language without one. */
    public MessageEntry withOverrides(final @Nullable String english, final @Nullable String german) {
        return new MessageEntry(
                key,
                bundle,
                this.english,
                this.german,
                englishTexts,
                germanTexts,
                english,
                german,
                inBundle,
                name,
                description,
                args,
                section,
                format,
                shown);
    }

    /** Every text the jar ships for the key in {@code language}, empty where it ships none. */
    public List<String> packaged(final String language) {
        return "de".equals(language) ? germanTexts : "en".equals(language) ? englishTexts : List.of();
    }

    /** Whether the jar's schema describes this key, which is what makes its placeholders checkable. */
    public boolean described() {
        return name != null;
    }
}
