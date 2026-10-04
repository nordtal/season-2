package eu.nordtal.s2.messages.text;

import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.messages.CheckMessages;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.value.Kind;
import eu.nordtal.s2.messages.value.Words;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The one validator of a message text; what it refuses is refused everywhere.
 * It is the build's check of every packaged text, Steward's save of an override and a process's load of overrides.
 */
public final class MessageCheck {

    /** The plural categories a case may name, besides an exact {@code =n}. */
    private static final List<String> PLURAL_ORDER = List.of("zero", "one", "two", "few", "many", "other");

    private static final Set<String> PLURAL_CASES = Set.copyOf(PLURAL_ORDER);

    private static final CheckMessages.Check SAYS = CheckMessages.TEXTS.check();

    /** Stands in for every word while a text is measured, so a limit holds in any language. */
    private static final Words MEASURE = (key, values) -> "........";

    /** Whose text is checked, which decides what it may use. */
    public enum Mode {
        /** A text the jar ships: colours only by tone, and every argument used. */
        PACKAGED,
        /** An admin's override: any colour, and an argument left out is only a warning. */
        OVERRIDE
    }

    /**
     * One thing wrong with a text.
     *
     * @param error whether it refuses the text, rather than warning about it
     * @param text  what is wrong, a message of the {@code check} bundle, for an admin to read
     */
    public record Problem(boolean error, MessageRef text) {}

    private final Declaration declaration;
    private final Mode mode;
    private final List<Problem> problems = new ArrayList<>();
    private final Set<String> used = new LinkedHashSet<>();

    private MessageCheck(final Declaration declaration, final Mode mode) {
        this.declaration = declaration;
        this.mode = mode;
    }

    /** Returns every problem of {@code text}, errors and warnings, in the order found; none for a good text. */
    public static List<Problem> check(final String text, final Declaration declaration, final Mode mode) {
        final MessageText parsed;
        try {
            parsed = MessageText.parse(text, declaration.markup());
        } catch (final MessageSyntaxException e) {
            return List.of(new Problem(true, e.reason()));
        }
        final MessageCheck check = new MessageCheck(declaration, mode);
        check.sequence(parsed.nodes());
        check.roles();
        if (check.problems.stream().noneMatch(Problem::error)) {
            check.length(parsed);
        }
        return List.copyOf(check.problems);
    }

    /** Returns only the errors of {@code text}, each in English as the packaged texts say it. */
    public static List<String> errors(final String text, final Declaration declaration, final Mode mode) {
        return check(text, declaration, mode).stream()
                .filter(Problem::error)
                .map(problem -> english(problem.text()))
                .toList();
    }

    /** Returns a problem in English with the packaged texts, for the build's refusal and a process's log. */
    public static String english(final MessageRef problem) {
        return English.MESSAGES.format(Locales.DEFAULT, problem);
    }

    /** The {@code check} bundle as packaged, loaded on the first problem told in English. */
    private static final class English {

        private static final Messages MESSAGES =
                Messages.load(MessageCheck.class.getClassLoader(), "messages/check", Locales.DEFAULT);

        private English() {}
    }

    /** Checks one run of nodes, a case's own: every tag it opens it closes. */
    private void sequence(final List<Node> nodes) {
        final Deque<String> open = new ArrayDeque<>();
        for (final Node node : nodes) {
            switch (node) {
                case Node.Literal ignored -> {}
                case Node.Pound ignored -> {}
                case Node.Value value -> value(value);
                case Node.Choice choice -> choice(choice);
                case Node.Tag tag -> tag(tag, open);
            }
        }
        for (final String tag : open) {
            error(SAYS.tag().neverClosed(tag));
        }
    }

    private void value(final Node.Value value) {
        final Kind kind = known(value.name());
        if (kind == null) {
            return;
        }
        if (value.kind() != null) {
            final Kind written = Kind.byToken(value.kind()).orElse(null);
            if (written == null) {
                error(SAYS.value().noKind(value.name(), value.kind(), tokens()));
                return;
            }
            if (written != kind) {
                error(SAYS.value().otherKind(value.name(), kind.token(), written.token()));
                return;
            }
        }
        if (value.style() != null && !kind.styles().contains(value.style())) {
            error(
                    kind.styles().isEmpty()
                            ? SAYS.value().styleless(value.name(), value.style(), kind.token())
                            : SAYS.value().noStyle(value.name(), value.style(), kind.token(), sorted(kind.styles())));
        }
    }

    private void choice(final Node.Choice choice) {
        final Kind kind = known(choice.name());
        final Kind wanted = choice.plural() ? Kind.NUMBER : Kind.CHOICE;
        if (kind != null && kind != wanted) {
            error(
                    choice.plural()
                            ? SAYS.value().pluralOn(choice.name(), kind.token())
                            : SAYS.value().selectOn(choice.name(), kind.token()));
        }
        for (final Map.Entry<String, List<Node>> branch : choice.cases().entrySet()) {
            final String key = branch.getKey();
            if (choice.plural() && !key.startsWith("=") && !PLURAL_CASES.contains(key)) {
                error(SAYS.value().pluralCase(choice.name(), key, PLURAL_ORDER));
            }
            sequence(branch.getValue());
        }
    }

    private void tag(final Node.Tag tag, final Deque<String> open) {
        final String name = tag.name();
        final boolean allowed = mode == Mode.PACKAGED ? Tags.packaged(name) : Tags.override(name);
        // A closing tag is refused with its opening one, or as closing nothing.
        if (!allowed && tag.shape() != Node.Tag.Shape.CLOSE) {
            error(
                    mode == Mode.PACKAGED && Tags.colour(name)
                            ? SAYS.tag().packagedColour(name, sorted(Tags.TONES))
                            : SAYS.tag().unknown(name));
        }
        if ("action".equals(name) && tag.shape() != Node.Tag.Shape.CLOSE) {
            final String action =
                    tag.args().isEmpty() ? null : tag.args().getFirst().literal();
            if (action == null || !declaration.actions().contains(action)) {
                error(SAYS.tag().unknownAction(action == null ? "" : action, sorted(declaration.actions())));
            } else {
                used.add(action);
            }
        }
        for (int index = 0; index < tag.args().size(); index++) {
            final Node.Tag.Arg arg = tag.args().get(index);
            final boolean takesValues = index == 1 && ("hover".equals(name) || "click".equals(name));
            for (final Node part : arg.parts()) {
                if (part instanceof final Node.Value value) {
                    if (!takesValues) {
                        error(SAYS.tag().valueInArgument(value.name(), name));
                    } else if (arg.quote() == 0) {
                        error(SAYS.tag().unquotedValue(value.name(), name));
                    } else {
                        value(value);
                    }
                }
            }
        }
        switch (tag.shape()) {
            case SELF_CLOSING -> {}
            case OPEN -> {
                if ("reset".equals(name)) {
                    open.clear();
                } else if (!Tags.isVoid(name)) {
                    open.push(name);
                }
            }
            case CLOSE -> {
                if (open.isEmpty() || !open.peek().equals(name)) {
                    error(
                            open.isEmpty()
                                    ? SAYS.tag().closesNothing(name)
                                    : SAYS.tag().closesOther(name, open.peek()));
                } else {
                    open.pop();
                }
            }
        }
    }

    /** The kind a placeholder is declared with, noting it as used; {@code null} and an error for an unknown one. */
    private @Nullable Kind known(final String name) {
        used.add(name);
        final Kind kind = declaration.values().get(name);
        if (kind == null) {
            error(SAYS.value().unknown(name, sorted(declaration.values().keySet())));
        }
        return kind;
    }

    private void roles() {
        for (final String role : declaration.roles()) {
            final boolean usedAtAll =
                    used.contains(role) || used.stream().anyMatch(name -> name.startsWith(role + "."));
            if (!usedAtAll) {
                problems.add(new Problem(mode == Mode.PACKAGED, SAYS.value().unshown(role)));
            }
        }
    }

    /** With every value at its example, the text must fit where it is shown. */
    private void length(final MessageText text) {
        if (declaration.limit() <= 0) {
            return;
        }
        final Map<String, Object> examples = new HashMap<>();
        declaration.examples().forEach((name, example) -> {
            final Kind kind = declaration.values().getOrDefault(name, Kind.TEXT);
            try {
                examples.put(name, kind.example(example));
            } catch (final RuntimeException unreadable) {
                examples.put(name, example);
            }
        });
        final String shown = PlainText.of(
                Filling.fill(text, examples, declaration.values(), ignored -> {}),
                Locale.ENGLISH,
                ZoneOffset.UTC,
                MEASURE);
        if (shown.length() > declaration.limit()) {
            error(SAYS.tooLong(shown.length(), declaration.limit()));
        }
    }

    private static List<String> sorted(final Set<String> names) {
        return List.copyOf(new TreeSet<>(names));
    }

    private static List<String> tokens() {
        return Arrays.stream(Kind.values()).map(Kind::token).toList();
    }

    private void error(final MessageRef text) {
        problems.add(new Problem(true, text));
    }
}
