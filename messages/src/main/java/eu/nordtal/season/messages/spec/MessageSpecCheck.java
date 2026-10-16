package eu.nordtal.season.messages.spec;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.messages.text.MessageSyntaxException;
import eu.nordtal.season.messages.text.MessageText;
import eu.nordtal.season.messages.text.Node;
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
        final Map<String, List<String>> english = MessageSchema.texts(spec, Locales.DEFAULT_TAG);
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
            if (entry.shown().isEmpty()) {
                problems.add(key + ": no @Shown names a place it appears");
            } else if (new HashSet<>(entry.shown()).size() != entry.shown().size()) {
                problems.add(key + ": its @Shown names a place twice");
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
            check(problems, key, Locales.DEFAULT_TAG, english.get(key), declaration);
            check(problems, key, "de", german.getOrDefault(key, List.of()), declaration);
        }
        problems.addAll(formerNames(spec, entries, declared));
        problems.addAll(takenKeys(spec, entries, declared));
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
            for (final String name : unpicked(text, declaration)) {
                problems.add(String.format(
                        "%s (%s): the example %s of %s names none of its cases",
                        key, language, declaration.examples().get(name), name));
            }
        }
    }

    /**
     * The selects whose example is no case of theirs but other, since an editor previews a select by its example.
     * A boolean is exempt: the one value its cases leave out is what other stands for.
     */
    private static List<String> unpicked(final String text, final Declaration declaration) {
        final List<String> names = new ArrayList<>();
        try {
            unpicked(MessageText.parse(text, declaration.markup()).nodes(), declaration.examples(), names);
        } catch (final MessageSyntaxException unreadable) {
            return List.of(); // MessageCheck names it.
        }
        return names;
    }

    private static void unpicked(final List<Node> nodes, final Map<String, String> examples, final List<String> names) {
        for (final Node node : nodes) {
            if (node instanceof final Node.Choice choice) {
                final String example = examples.get(choice.name());
                final boolean picked = example == null
                        || "true".equals(example)
                        || "false".equals(example)
                        || (!"other".equals(example) && choice.cases().containsKey(example));
                if (!choice.plural() && !picked) {
                    names.add(choice.name());
                }
                choice.cases().values().forEach(branch -> unpicked(branch, examples, names));
            } else if (node instanceof final Node.Tag tag) {
                tag.args().forEach(arg -> unpicked(arg.parts(), examples, names));
            }
        }
    }

    /** A key a message value takes is one: in this bundle a key it declares, in another as {@code bundle/key}. */
    private static List<String> takenKeys(
            final Class<?> spec, final List<MessageSchema.Entry> entries, final Set<String> declared) {
        final List<String> problems = new ArrayList<>();
        final String bundle = MessageSchema.bundle(spec);
        for (final MessageSchema.Entry entry : entries) {
            for (final MessageSchema.Arg arg : entry.args()) {
                for (final String taken : Objects.requireNonNullElse(arg.takes(), List.<String>of())) {
                    final String own = taken.startsWith(bundle + "/") ? taken.substring(bundle.length() + 1) : taken;
                    if (!FORMER.matcher(taken).matches()) {
                        problems.add(entry.key() + ": @Takes names " + taken + ", which is neither key nor bundle/key");
                    } else if (!own.contains("/") && !declared.contains(own)) {
                        problems.add(entry.key() + ": @Takes names " + taken + ", which this bundle does not declare");
                    }
                }
            }
        }
        return problems;
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
