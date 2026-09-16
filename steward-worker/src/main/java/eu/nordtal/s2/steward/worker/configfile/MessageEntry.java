package eu.nordtal.s2.steward.worker.configfile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 */
public record MessageEntry(
        @NotNull String key,
        @Nullable String english,
        @Nullable String german,
        @Nullable String overrideEnglish,
        @Nullable String overrideGerman,
        boolean inBundle) {
}
