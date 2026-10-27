package eu.nordtal.season.messages.text;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messages.CheckMessages;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.TermMessages;
import eu.nordtal.season.messages.value.Case;
import eu.nordtal.season.messages.value.Kind;
import eu.nordtal.season.messages.value.Words;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The one validator of a message text.
 * The build, Steward's save of an override and a process's load of overrides all use it.
 */
public final class MessageCheck {

    /** The plural categories a case may name, besides an exact {@code =n}. */
    private static final List<String> PLURAL_ORDER = List.of("zero", "one", "two", "few", "many", "other");

    private static final Set<String> PLURAL_CASES = Set.copyOf(PLURAL_ORDER);

    private static final CheckMessages.Check SAYS = CheckMessages.TEXTS.check();

    /** Maps each word of {@link TermMessages#SYNONYMS} to the key of its term. */
    private static final Map<Pattern, String> SYNONYMS = synonyms();

    private static final Words MEASURE = (key, values) -> "........";

    /** Tells whose text is checked. That decides what the text may use. */
    public enum Mode {
        /** A text the jar ships. It uses colours only by tone and uses every argument. */
        PACKAGED,
        /** An admin's override. It may use any colour, and an unused argument is only a warning. */
        OVERRIDE
    }

    /**
     * @param error whether the problem refuses the text instead of warning about it
     * @param text the problem as a message of the {@code check} bundle
     */
    public record Problem(boolean error, MessageRef text) {}

    private final Declaration declaration;
    private final Mode mode;
    private final Filling.Blocks blocks;
    private final List<Problem> problems = new ArrayList<>();
    private final Set<String> used = new LinkedHashSet<>();

    /** Holds the value of each open {@code <click:open_url>} tag, or an empty name for a literal address. */
    private final Deque<String> links = new ArrayDeque<>();

    private MessageCheck(final Declaration declaration, final Mode mode, final Filling.Blocks blocks) {
        this.declaration = declaration;
        this.mode = mode;
        this.blocks = blocks;
    }

    /** The packaged English building blocks, which the length check draws unless it is given others. */
    public static final Filling.Blocks PACKAGED_BLOCKS = key -> Packaged.BLOCKS.block(key);

    public static List<Problem> check(final String text, final Declaration declaration, final Mode mode) {
        return check(text, declaration, mode, PACKAGED_BLOCKS);
    }

    /** Checks {@code text} with the length that {@code blocks} draw, such as the blocks of a save not yet written. */
    public static List<Problem> check(
            final String text, final Declaration declaration, final Mode mode, final Filling.Blocks blocks) {
        final MessageText parsed;
        try {
            parsed = MessageText.parse(text, declaration.markup());
        } catch (final MessageSyntaxException e) {
            return List.of(new Problem(true, e.reason()));
        }
        final MessageCheck check = new MessageCheck(declaration, mode, blocks);
        check.sequence(parsed.nodes());
        check.roles();
        if (check.problems.stream().noneMatch(Problem::error)) {
            check.length(parsed);
        }
        return List.copyOf(check.problems);
    }

    public static List<String> errors(final String text, final Declaration declaration, final Mode mode) {
        return check(text, declaration, mode).stream()
                .filter(Problem::error)
                .map(problem -> english(problem.text()))
                .toList();
    }

    public static String english(final MessageRef problem) {
        return English.MESSAGES.format(Locales.DEFAULT, problem);
    }

    /** The packaged English building blocks, which the length check draws. */
    private static final class Packaged {

        private static final Filling.Blocks BLOCKS = Messages.load(
                        MessageCheck.class.getClassLoader(), "messages/" + Messages.TERMS, Locales.DEFAULT)
                .blocks(Locales.DEFAULT);

        private Packaged() {}
    }

    private static final class English {

        private static final Messages MESSAGES =
                Messages.load(MessageCheck.class.getClassLoader(), "messages/check", Locales.DEFAULT);

        private English() {}
    }

    /** Every tag a run opens must close in it. */
    private void sequence(final List<Node> nodes) {
        final Deque<String> open = new ArrayDeque<>();
        for (final Node node : nodes) {
            switch (node) {
                case Node.Literal literal -> synonyms(literal.text());
                case Node.Pound ignored -> {}
                case Node.Value value -> {
                    if (links.contains(value.name())) {
                        problems.add(
                                new Problem(mode == Mode.PACKAGED, SAYS.tag().linkShowsAddress(value.name())));
                    }
                    value(value);
                }
                case Node.Choice choice -> choice(choice);
                case Node.Block block -> block(block);
                case Node.Tag tag -> {
                    tag(tag, open);
                    link(tag);
                }
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

    /** Each value of the block is bound to a value of this text, or takes one of the same name. */
    private void block(final Node.Block reference) {
        final String key = reference.key();
        final Declaration block = Messages.blocks().get(key);
        if (block == null) {
            error(SAYS.block().unknown(key));
            return;
        }
        for (final String name : reference.with().keySet()) {
            if (!block.roles().contains(name)) {
                error(SAYS.block().noValue(key, name, sorted(block.roles())));
            }
        }
        for (final String role : block.roles()) {
            final String outer = reference.with().getOrDefault(role, role);
            final Kind wanted = block.values().get(role);
            if (Node.Block.number(outer) || (wanted == Kind.CHOICE && !offers(declaration, outer))) {
                constant(key, role, wanted, outer, reference.with().containsKey(role));
                continue;
            }
            if (!offers(declaration, outer)) {
                error(SAYS.block()
                        .unbound(key, role, sorted(declaration.values().keySet())));
                continue;
            }
            used.add(outer);
            final Kind given = declaration.values().get(outer);
            if (wanted != null && given != null && wanted != given) {
                error(SAYS.block().otherKind(key, role, wanted.token(), given.token()));
            }
        }
    }

    /** A constant fits a number as a number, and a choice as one of its cases.
     * {@code case} takes a grammatical case, or {@code other} unbound; another choice a case of the English text.
     */
    private void constant(
            final String key,
            final String role,
            final @Nullable Kind wanted,
            final String constant,
            final boolean bound) {
        if (wanted == Kind.NUMBER && Node.Block.number(constant)) {
            return;
        }
        if (wanted != Kind.CHOICE) {
            error(SAYS.block()
                    .otherKind(key, role, wanted == null ? Kind.TEXT.token() : wanted.token(), Kind.NUMBER.token()));
            return;
        }
        if (role.equals(Node.Block.CASE) && !bound) {
            return;
        }
        final List<String> cases = role.equals(Node.Block.CASE) ? Kind.choices(Case.class) : cases(key, role);
        if (!cases.contains(constant)) {
            error(SAYS.block().noCase(key, role, constant, cases));
        }
    }

    /** Returns the cases that the English text of block {@code key} names for its choice {@code role}. */
    private static List<String> cases(final String key, final String role) {
        final Filling.Block block = Packaged.BLOCKS.block(key);
        final List<String> cases = new ArrayList<>();
        if (block != null) {
            collect(block.text().nodes(), role, cases);
        }
        return cases;
    }

    private static void collect(final List<Node> nodes, final String role, final List<String> into) {
        for (final Node node : nodes) {
            if (node instanceof final Node.Choice choice) {
                if (!choice.plural() && choice.name().equals(role)) {
                    choice.cases().keySet().stream()
                            .filter(name -> !"other".equals(name) && !into.contains(name))
                            .forEach(into::add);
                }
                choice.cases().values().forEach(branch -> collect(branch, role, into));
            }
        }
    }

    /** Returns whether {@code declared} offers {@code name} as a value or as a context with attributes. */
    private static boolean offers(final Declaration declared, final String name) {
        return declared.values().containsKey(name)
                || declared.values().keySet().stream().anyMatch(value -> value.startsWith(name + "."));
    }

    private void tag(final Node.Tag tag, final Deque<String> open) {
        final String name = tag.name();
        final boolean allowed = mode == Mode.PACKAGED ? Tags.packaged(name) : Tags.override(name);
        // A closing tag is refused together with its opening tag, or as closing nothing.
        if (!allowed && tag.shape() != Node.Tag.Shape.CLOSE) {
            error(
                    mode == Mode.PACKAGED && Tags.colour(name)
                            ? SAYS.tag().packagedColour(name, sorted(Tags.TONES))
                            : SAYS.tag().unknown(name));
        }
        if (declaration.oneLine() && Tags.isLineBreak(name)) {
            problems.add(new Problem(mode == Mode.PACKAGED, SAYS.tag().lineBreakOnOneLine(name)));
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
                } else if (part instanceof final Node.Literal literal && takesValues && "hover".equals(name)) {
                    synonyms(literal.text());
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

    private void link(final Node.Tag tag) {
        if (!"click".equals(tag.name())) {
            return;
        }
        if (tag.shape() == Node.Tag.Shape.CLOSE) {
            links.pollFirst();
            return;
        }
        if (tag.shape() == Node.Tag.Shape.OPEN
                && tag.args().size() == 2
                && "open_url".equals(tag.args().getFirst().literal())) {
            links.push(tag.args().get(1).parts().stream()
                    .filter(Node.Value.class::isInstance)
                    .map(part -> ((Node.Value) part).name())
                    .findFirst()
                    .orElse(""));
        } else if (tag.shape() == Node.Tag.Shape.OPEN) {
            links.push("");
        }
    }

    /** A text writes a game term by its one name. */
    private void synonyms(final String literal) {
        SYNONYMS.forEach((word, term) -> {
            final Matcher found = word.matcher(literal);
            while (found.find()) {
                problems.add(new Problem(mode == Mode.PACKAGED, SAYS.synonym(found.group(), term)));
            }
        });
    }

    private static Map<Pattern, String> synonyms() {
        final Map<Pattern, String> patterns = new HashMap<>();
        TermMessages.SYNONYMS.forEach((term, words) -> words.forEach(word -> patterns.put(
                Pattern.compile(
                        "(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                term)));
        return Map.copyOf(patterns);
    }

    /** Returns {@code null} and reports an error for an unknown placeholder. */
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
            if (role.equals(Node.Block.CASE)) {
                continue; // a language without cases never names the case its block is shown in
            }
            final boolean usedAtAll =
                    used.contains(role) || used.stream().anyMatch(name -> name.startsWith(role + "."));
            if (!usedAtAll) {
                problems.add(new Problem(mode == Mode.PACKAGED, SAYS.value().unshown(role)));
            }
        }
    }

    /** Checks that the text fits with every value at its example. */
    private void length(final MessageText text) {
        if (declaration.limit() <= 0) {
            return;
        }
        final int shown = shown(text, declaration, blocks).length();
        if (shown > declaration.limit()) {
            error(SAYS.tooLong(shown, declaration.limit()));
        }
    }

    /** Returns how many characters the text shows with every value at its example. */
    public static int shownLength(final MessageText text, final Declaration declaration) {
        return shown(text, declaration, Packaged.BLOCKS).length();
    }

    /** Returns the text as it shows with every value at its example, each value a run of dots. */
    public static String shown(final MessageText text, final Declaration declaration, final Filling.Blocks blocks) {
        final Map<String, Object> examples = new HashMap<>();
        declaration.examples().forEach((name, example) -> {
            final Object read = declaration.read(name, example);
            if (read != null) {
                examples.put(name, read);
            }
        });
        return PlainText.of(
                Filling.fill(text, examples, declaration.values(), ignored -> {}, blocks),
                Locale.ENGLISH,
                ZoneOffset.UTC,
                MEASURE);
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
