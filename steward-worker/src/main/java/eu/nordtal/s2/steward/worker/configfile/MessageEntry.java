package eu.nordtal.s2.steward.worker.configfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One message key, in both languages, packaged and overridden.
 *
 * <p>Both layers are carried side by side rather than pre-merged, because the interface has to show
 * an overridden line as visibly overridden (steward/48) - a merged view could not tell the two
 * apart. {@code eu.nordtal.s2.common.message.Messages} does the actual merge at runtime; this is a
 * read-only picture of the same two layers for a human to look at and edit.</p>
 *
 * @param key             the dotted key, e.g. {@code contribution.title}
 * @param english         the English text the jar ships, or {@code null} if no packaged bundle
 *                        declares it in English - which only happens for a key an override alone
 *                        introduced, see {@link #inBundle()}
 * @param german          the German text the jar ships, or {@code null} if the German bundle has no
 *                        line for this key - a real state, not a bug: an untranslated key falls back
 *                        to {@link #english()} at runtime, and the interface has to be able to show
 *                        that gap rather than papering over it with the English text
 * @param overrideEnglish the operator's override of the English line, or {@code null} if there is
 *                        none
 * @param overrideGerman  the operator's override of the German line, or {@code null} if there is none
 * @param inBundle        whether this key is one the jar actually declares, in either language.
 *                        {@code false} only for a key that exists solely because an override file
 *                        names it - a typo, or a key a later release retired. Never hidden for it
 *                        (the same rule steward/50 gives {@code ConfigEntry#inSchema})
 * @param name            the name an admin reads, from the jar's {@code schema.json}; {@code null}
 *                        for a key the schema does not describe - an override-only key, or a jar
 *                        built before schemas existed
 * @param description     a sentence for a hard case, or {@code null}
 * @param args            the placeholders the message is filled with, in parameter order; empty
 *                        when the schema does not describe the key
 * @param section         the names of the sections around the key, outermost first; {@code null} for
 *                        a section that has none
 * @param format          how the text is written - {@code MINIMESSAGE}, {@code DISCORD_MARKDOWN} or
 *                        {@code PLAIN} - or {@code null} when the schema does not say
 * @param shown           where the text is shown, e.g. {@code TITLE}, or {@code null} when the schema
 *                        does not say
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
        // A section without a name is a null here, which List.copyOf would refuse.
        section = Collections.unmodifiableList(new ArrayList<>(section));
    }

    /** Whether the jar's schema describes this key, which is what makes its placeholders checkable. */
    public boolean described() {
        return name != null;
    }
}
