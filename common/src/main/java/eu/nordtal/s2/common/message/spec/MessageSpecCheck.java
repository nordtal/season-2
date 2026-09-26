package eu.nordtal.s2.common.message.spec;

import eu.nordtal.s2.common.message.context.Contexts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds a {@link MessageSpec} and its bundle to each other: every key of the English file has a
 * method and every method a key, every message and section has a {@link Name}, and the
 * <code>{placeholders}</code> of each text, English and German, are exactly the method's plain
 * arguments - a Component argument has to appear as its {@code <tag>} instead. A role is used through
 * at least one <code>{role.property}</code> its context type has; the global roles may be used
 * anywhere and need not be.
 *
 * <p>Each module with a bundle runs this from one test, and the schema build step refuses to
 * write a schema it fails.</p>
 */
public final class MessageSpecCheck {

    /** The same shape {@code Messages#format} substitutes. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

    private MessageSpecCheck() {}

    /** @return one line per problem; empty when spec and bundle agree */
    public static List<String> problems(final Class<?> spec) {
        final List<String> problems = new ArrayList<>();
        final List<MessageSchema.Entry> entries = MessageSchema.entries(spec);
        final Properties english = MessageSchema.bundleFile(spec, "en");
        final Properties german = MessageSchema.bundleFile(spec, "de");
        if (english.isEmpty()) {
            problems.add("messages/" + MessageSchema.bundle(spec) + "/en.properties is missing or empty");
        }

        final Map<String, Class<?>> contexts = MessageSchema.contextTypes(spec);
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
            final List<String> plain = new ArrayList<>();
            final Map<String, List<String>> roles = new LinkedHashMap<>();
            final List<String> components = new ArrayList<>();
            for (final MessageSchema.Arg arg : entry.args()) {
                if (arg.context() != null) {
                    roles.put(arg.name(), properties(contexts, arg.context()));
                } else if (arg.component()) {
                    components.add(arg.name());
                } else {
                    plain.add(arg.name());
                }
            }
            if (new HashSet<>(plain).size() + roles.size() + new HashSet<>(components).size()
                    != entry.args().size()) {
                problems.add(key + ": two parameters fill the same placeholder");
            }
            if (!english.containsKey(key)) {
                problems.add(key + ": the method has no line in en.properties");
                continue;
            }
            check(problems, key, "en", english.getProperty(key), plain, roles, components);
            if (german.containsKey(key)) {
                check(problems, key, "de", german.getProperty(key), plain, roles, components);
            }
        }
        for (final String key : new TreeSet<>(english.stringPropertyNames())) {
            if (!declared.contains(key)) {
                problems.add(key + ": in en.properties, but no method declares it");
            }
        }
        return problems;
    }

    private static List<String> properties(final Map<String, Class<?>> contexts, final String type) {
        return Contexts.properties(contexts.get(type));
    }

    private static void check(
            final List<String> problems,
            final String key,
            final String language,
            final String text,
            final List<String> plain,
            final Map<String, List<String>> roles,
            final List<String> components) {
        final Set<String> found = new TreeSet<>();
        final Set<String> dotted = new TreeSet<>();
        final Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            (matcher.group(1).contains(".") ? dotted : found).add(matcher.group(1));
        }
        if (!found.equals(new TreeSet<>(plain))) {
            problems.add(key + " (" + language + "): the text names " + found + ", the method declares "
                    + new TreeSet<>(plain));
        }
        final Set<String> usedRoles = new HashSet<>();
        for (final String name : dotted) {
            final String role = name.substring(0, name.indexOf('.'));
            final String property = name.substring(name.indexOf('.') + 1);
            final List<String> known = roles.containsKey(role)
                    ? roles.get(role)
                    : Contexts.GLOBALS.containsKey(role) ? Contexts.properties(Contexts.GLOBALS.get(role)) : null;
            if (known == null || !known.contains(property)) {
                problems.add(key + " (" + language + "): the text names {" + name + "}, which nothing declares");
            }
            usedRoles.add(role);
        }
        for (final String role : roles.keySet()) {
            if (!usedRoles.contains(role)) {
                problems.add(key + " (" + language + "): the role " + role + " is never used");
            }
        }
        for (final String component : components) {
            if (!text.contains("<" + component + ">") && !text.contains("<" + component + "/>")) {
                problems.add(
                        key + " (" + language + "): the method declares <" + component + ">, the text never uses it");
            }
        }
    }
}
