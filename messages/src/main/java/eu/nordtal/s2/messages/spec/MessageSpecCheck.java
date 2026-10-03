package eu.nordtal.s2.messages.spec;

import eu.nordtal.s2.messages.text.Declaration;
import eu.nordtal.s2.messages.text.MessageCheck;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Holds a {@link MessageSpec} and its bundle to each other, key by key, through the one validator.
 * Every packaged text is checked as {@link MessageCheck.Mode#PACKAGED}.
 */
public final class MessageSpecCheck {

    /** A former name: a key of this bundle, or {@code bundle/key}. */
    private static final Pattern FORMER = Pattern.compile("([a-z][a-z0-9-]*/)?[A-Za-z0-9][A-Za-z0-9_.-]*");

    private MessageSpecCheck() {}

    /** Returns one line per problem, empty when spec and bundle agree. */
    public static List<String> problems(final Class<?> spec) {
        final List<String> problems = new ArrayList<>();
        final MessageSchema.Bundle schema = MessageSchema.of(spec);
        final List<MessageSchema.Entry> entries = schema.messages();
        final Map<String, List<String>> english = MessageSchema.texts(spec, "en");
        final Map<String, List<String>> german = MessageSchema.texts(spec, "de");
        if (english.isEmpty()) {
            problems.add("messages/" + MessageSchema.bundle(spec) + "/en.properties is missing or empty");
        }

        final Set<String> declared = new HashSet<>();
        for (final MessageSchema.Entry entry : entries) {
            final String key = entry.key();
            if (!declared.add(key)) {
                problems.add(key + ": declared by more than one method");
            }
            if (entry.name() == null || entry.name().isBlank()) {
                problems.add(key + ": no @Name");
            }
            if (entry.section().stream().anyMatch(name -> name == null || name.isBlank())) {
                problems.add(key + ": a section around it has no @Name");
            }
            if (entry.args().stream().anyMatch(arg -> arg.name() == null)) {
                problems.add(key + ": a parameter has no @Arg");
                continue;
            }
            if (new HashSet<>(entry.args().stream().map(MessageSchema.Arg::name).toList()).size()
                    != entry.args().size()) {
                problems.add(key + ": two parameters fill the same placeholder");
            }
            if (!english.containsKey(key)) {
                problems.add(key + ": the method has no line in en.properties");
                continue;
            }
            final Declaration declaration = schema.declaration(entry);
            check(problems, key, "en", english.get(key), declaration);
            check(problems, key, "de", german.getOrDefault(key, List.of()), declaration);
        }
        problems.addAll(formerNames(spec, entries, declared));
        for (final String key : new TreeSet<>(english.keySet())) {
            if (!declared.contains(key)) {
                problems.add(key + ": in en.properties, but no method declares it");
            }
        }
        return problems;
    }

    private static void check(
            final List<String> problems,
            final String key,
            final String language,
            final List<String> texts,
            final Declaration declaration) {
        for (final String text : texts) {
            MessageCheck.errors(text, declaration, MessageCheck.Mode.PACKAGED)
                    .forEach(error -> problems.add(key + " (" + language + "): " + error));
        }
    }

    /** A former name must be one, not a key this bundle still declares. */
    private static List<String> formerNames(
            final Class<?> spec, final List<MessageSchema.Entry> entries, final Set<String> declared) {
        final List<String> problems = new ArrayList<>();
        final String bundle = MessageSchema.bundle(spec);
        for (final MessageSchema.Entry entry : entries) {
            for (final String former : Objects.requireNonNullElse(entry.formerly(), List.<String>of())) {
                final String own = former.startsWith(bundle + "/") ? former.substring(bundle.length() + 1) : former;
                if (!FORMER.matcher(former).matches()) {
                    problems.add(entry.key() + ": @Formerly names " + former + ", which is neither key nor bundle/key");
                } else if (declared.contains(own)) {
                    problems.add(entry.key() + ": @Formerly names " + former + ", which this bundle still declares");
                }
            }
        }
        return problems;
    }
}
