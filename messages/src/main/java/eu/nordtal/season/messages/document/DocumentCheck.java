package eu.nordtal.season.messages.document;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.messages.text.MessageSyntaxException;
import eu.nordtal.season.messages.text.MessageText;
import eu.nordtal.season.messages.text.Node;
import eu.nordtal.season.messages.text.Typography;
import eu.nordtal.season.messages.value.Kind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks a document against its declaration, against what Discord draws and against the rules of every text.
 * The build checks each packaged document, and Steward an admin's document on a save.
 */
public final class DocumentCheck {

    /** How many components a message holds. A test of the bot holds these numbers to JDA's constants. */
    public static final int COMPONENTS = 40;

    public static final int TEXT = 4000;
    public static final int PLAIN = 2000;
    public static final int BUTTON_LABEL = 80;
    public static final int SELECT_PLACEHOLDER = 100;
    public static final int OPTIONS = 25;
    public static final int OPTION_LABEL = 100;
    public static final int SECTION_TEXTS = 3;
    public static final int GALLERY_ITEMS = 10;
    public static final int ROW_BUTTONS = 5;
    public static final int MODAL_FIELDS = 5;
    public static final int MODAL_TITLE = 45;
    public static final int FIELD_LABEL = 45;
    public static final int FIELD_PLACEHOLDER = 100;
    public static final int CHANNEL_NAME = 100;

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[A-Z]{2})?");
    private static final Pattern ACCENT = Pattern.compile("#[0-9a-f]{6}");
    private static final Pattern MEDIA =
            Pattern.compile("(attachment://[A-Za-z0-9_.-]+|upload://[1-9][0-9]*|https://\\S+)");
    private static final Pattern LINK = Pattern.compile("https://\\S+");

    /** @param error whether the problem refuses the document instead of warning about it */
    public record Problem(boolean error, String text) {}

    private final DocumentDeclaration declaration;
    private final MessageCheck.Mode mode;
    private final Set<String> languages;
    private final List<Problem> problems = new ArrayList<>();
    private final Map<String, Integer> placed = new LinkedHashMap<>();
    private final Set<String> used = new HashSet<>();

    private DocumentCheck(
            final DocumentDeclaration declaration, final Document document, final MessageCheck.Mode mode) {
        this.declaration = declaration;
        this.mode = mode;
        this.languages = document.languages();
    }

    public static List<Problem> check(
            final DocumentDeclaration declaration, final Document document, final MessageCheck.Mode mode) {
        final DocumentCheck check = new DocumentCheck(declaration, document, mode);
        check.shape(document);
        check.parts(document.parts(), false, false);
        check.slots();
        check.limits(document);
        check.unnamed();
        return List.copyOf(check.problems);
    }

    /** Returns how many components the document draws at its largest, a block per language once per language. */
    public static int components(final DocumentDeclaration declaration, final Document document) {
        return new Measure(declaration, document.languages()).components(document.parts());
    }

    /** Returns the text of each problem that refuses the document. */
    public static List<String> errors(
            final DocumentDeclaration declaration, final Document document, final MessageCheck.Mode mode) {
        return check(declaration, document, mode).stream()
                .filter(Problem::error)
                .map(Problem::text)
                .toList();
    }

    private void error(final String text) {
        problems.add(new Problem(true, text));
    }

    private void shape(final Document document) {
        final List<Part> parts = document.parts();
        if (parts.isEmpty()) {
            error("it has no parts");
        }
        switch (declaration.shape()) {
            case MESSAGE -> {}
            case MODAL -> {
                final boolean titled = titled(parts);
                final boolean fields = parts.stream()
                        .skip(1)
                        .allMatch(part -> part.type() == Part.Type.ROW
                                && part.allItems().size() == 1
                                && part.allItems().getFirst().slot() != null);
                if (!titled || !fields) {
                    error("a modal holds something other than its title and one field per row");
                }
                if (parts.size() - 1 > MODAL_FIELDS) {
                    error("a modal holds " + (parts.size() - 1) + " fields, Discord takes 1 to " + MODAL_FIELDS);
                }
            }
            case MENU -> {
                if (!titled(parts)
                        || parts.stream()
                                .skip(1)
                                .anyMatch(part -> part.type() != Part.Type.ITEM && part.type() != Part.Type.LABEL)) {
                    error("a menu holds something other than its title, then items and labels");
                }
            }
            case PLAIN,
                    CHANNEL_NAME,
                    CHAT,
                    TAB_LIST,
                    KICK_SCREEN,
                    TITLE,
                    BOSS_BAR,
                    SERVER_LIST,
                    ACTION_BAR,
                    HOLOGRAM -> {
                if (parts.stream()
                        .anyMatch(part -> part.type() != Part.Type.TEXT
                                || part.allLines().stream().anyMatch(line -> line.list() != null))) {
                    error("a " + noun(declaration.shape()) + " holds something other than text blocks of lines");
                }
            }
        }
    }

    private static boolean titled(final List<Part> parts) {
        return !parts.isEmpty()
                && parts.getFirst().type() == Part.Type.TEXT
                && parts.getFirst().allLines().size() == 1
                && parts.getFirst().allLines().getFirst().style() == Line.Style.TITLE;
    }

    private void parts(final List<Part> parts, final boolean inContainer, final boolean inLanguages) {
        for (final Part part : parts) {
            condition(part.when(), Map.of());
            part.allMedia().stream()
                    .filter(address -> !MEDIA.matcher(address).matches())
                    .forEach(address ->
                            error("an image is at " + address + ", not attachment://, upload:// or https://"));
            switch (part.type()) {
                case CONTAINER -> {
                    if (inContainer) {
                        error("a container holds another container");
                    }
                    if (part.accent() != null && !ACCENT.matcher(part.accent()).matches()) {
                        error("a container's accent " + part.accent() + " is not #rrggbb");
                    }
                }
                case TEXT -> lines(part);
                case SECTION -> section(part);
                case GALLERY -> {
                    if (part.allMedia().isEmpty() || part.allMedia().size() > GALLERY_ITEMS) {
                        error("a gallery holds " + part.allMedia().size() + " images, Discord draws 1 to "
                                + GALLERY_ITEMS);
                    }
                }
                case FILE -> {
                    if (part.allMedia().size() != 1) {
                        error("a file holds " + part.allMedia().size() + " addresses, Discord takes 1");
                    }
                    part.allMedia().stream()
                            .filter(address -> address.startsWith("https://"))
                            .forEach(address -> error("a file is at " + address
                                    + ", but Discord packs only attachment:// or upload:// files"));
                }
                case ROW -> row(part);
                case LANGUAGES -> {
                    if (inLanguages) {
                        error("a block per language holds another");
                    }
                    place(part.slot(), Slot.Type.LANGUAGES);
                }
                case ITEM -> menuItem(part);
                case LABEL -> label(part);
                case SEPARATOR -> {}
            }
            parts(
                    part.inner(),
                    inContainer || part.type() == Part.Type.CONTAINER,
                    inLanguages || part.type() == Part.Type.LANGUAGES);
        }
    }

    private void lines(final Part part) {
        if (part.allLines().isEmpty()) {
            error("a text block has no lines");
        }
        final boolean modalTitle = declaration.shape() == DocumentSpec.Shape.MODAL;
        for (final Line line : part.allLines()) {
            condition(line.when(), Map.of());
            if (line.list() != null) {
                final Slot slot = place(line.list(), Slot.Type.LIST);
                text(line.text(), "a data list's line", TEXT, false, slot == null ? Map.of() : slot.values());
            } else if (modalTitle) {
                text(line.text(), "a modal's title", MODAL_TITLE, true, Map.of());
            } else {
                switch (declaration.shape()) {
                    case CHANNEL_NAME -> text(line.text(), "a channel's name", CHANNEL_NAME, true, Map.of());
                    case PLAIN -> text(line.text(), "a message's content", PLAIN, false, Map.of());
                    case MESSAGE, MODAL -> text(line.text(), "a text block", TEXT, false, Map.of());
                    case MENU -> text(line.text(), "a menu's title", 0, true, true, Map.of());
                    case CHAT, TAB_LIST, KICK_SCREEN, SERVER_LIST, HOLOGRAM ->
                        text(line.text(), "a " + noun(declaration.shape()) + "'s line", 0, false, true, Map.of());
                    case TITLE -> text(line.text(), "a title's line", 0, true, true, Map.of());
                    case BOSS_BAR -> text(line.text(), "a boss bar's line", 0, true, Map.of());
                    case ACTION_BAR -> text(line.text(), "an action bar's line", 0, true, true, Map.of());
                }
            }
        }
    }

    /** Checks a menu's item: a name and lore lines, each in MiniMessage, filled with the item's values. */
    private void menuItem(final Part part) {
        final Slot slot = place(part.slot(), Slot.Type.ITEM);
        final Map<String, Value> values = slot == null ? Map.of() : slot.values();
        if (part.allLines().isEmpty()) {
            error("the item " + part.slot() + " has no name");
        }
        for (final Line line : part.allLines()) {
            condition(line.when(), values);
            final Map<String, Value> all = new LinkedHashMap<>(values);
            if (line.list() != null) {
                final Slot list = place(line.list(), Slot.Type.LIST);
                if (list != null) {
                    all.putAll(list.values());
                }
            }
            text(line.text(), "the item " + part.slot() + "'s line", 0, true, true, all);
        }
    }

    /** Checks a menu's label: one plain line, which the window cuts to its room. */
    private void label(final Part part) {
        final Slot slot = place(part.slot(), Slot.Type.LABEL);
        if (part.allLines().size() != 1 || part.allLines().getFirst().list() != null) {
            error("the label " + part.slot() + " holds " + part.allLines().size() + " lines, a label takes one");
        }
        for (final Line line : part.allLines()) {
            final Map<String, Value> values = slot == null ? Map.of() : slot.values();
            condition(line.when(), values);
            text(line.text(), "the label " + part.slot(), 0, true, false, values);
            if (line.text().languages().values().stream()
                    .flatMap(List::stream)
                    .anyMatch(version -> version.indexOf('<') >= 0 || version.indexOf('>') >= 0)) {
                error("the label " + part.slot() + " holds an angle bracket, which the menu's font cannot draw");
            }
        }
    }

    private void section(final Part part) {
        final List<Part> texts = part.inner();
        if (texts.isEmpty() || texts.size() > SECTION_TEXTS) {
            error("a section holds " + texts.size() + " text blocks, Discord draws 1 to " + SECTION_TEXTS);
        }
        if (texts.stream().anyMatch(text -> text.type() != Part.Type.TEXT)) {
            error("a section holds something other than text blocks");
        }
        if ((part.button() == null) == (part.thumbnail() == null)) {
            error("a section has not either a button or a thumbnail beside it");
        }
        if (part.thumbnail() != null && !MEDIA.matcher(part.thumbnail()).matches()) {
            error("an image is at " + part.thumbnail() + ", not attachment://, upload:// or https://");
        }
        if (part.button() != null) {
            item(part.button(), true);
        }
    }

    private void row(final Part part) {
        final List<Item> items = part.allItems();
        if (items.isEmpty() || items.size() > ROW_BUTTONS) {
            error("a row holds " + items.size() + " items, Discord draws 1 to " + ROW_BUTTONS);
        }
        for (final Item item : items) {
            final Slot slot = item(item, false);
            if (slot != null && slot.type() != Slot.Type.BUTTON && items.size() > 1) {
                error("a row holds the " + noun(slot.type()) + " " + slot.id() + " beside something else");
            }
        }
    }

    /** Checks a row item or a section's button and returns its slot, if it has a declared one. */
    private @Nullable Slot item(final Item item, final boolean inSection) {
        condition(item.when(), Map.of());
        if (item.slot() == null) {
            if (item.link() == null || !LINK.matcher(item.link()).matches()) {
                error("a link button opens no https:// address");
            }
            required(item.label(), "a link button's label", BUTTON_LABEL, Map.of());
            return null;
        }
        final Slot slot = declaration.slot(item.slot()).orElse(null);
        final Slot.Type type = slot == null ? Slot.Type.BUTTON : slot.type();
        if (inSection || declaration.shape() == DocumentSpec.Shape.MESSAGE) {
            if ((type != Slot.Type.BUTTON && type != Slot.Type.SELECT) || (inSection && type != Slot.Type.BUTTON)) {
                error("it places the " + noun(type) + " " + item.slot() + " where a button belongs");
            }
        } else if (type != Slot.Type.INPUT) {
            error("it places the " + noun(type) + " " + item.slot() + " where a field belongs");
        }
        place(item.slot(), type);
        switch (type) {
            case BUTTON -> required(item.label(), "a button's label", BUTTON_LABEL, Map.of());
            case SELECT -> {
                required(item.placeholder(), "a select's placeholder", SELECT_PLACEHOLDER, Map.of());
                if (slot != null && slot.lined()) {
                    required(item.option(), "a select's option line", OPTION_LABEL, slot.values());
                } else if (item.option() != null) {
                    error("the select " + item.slot() + " has an option line, but the code labels its options");
                }
            }
            case INPUT -> {
                required(item.label(), "a field's label", FIELD_LABEL, Map.of());
                if (item.placeholder() != null) {
                    text(item.placeholder(), "a field's placeholder", FIELD_PLACEHOLDER, true, Map.of());
                }
            }
            case LIST, LANGUAGES, ITEM, LABEL -> {}
        }
        return slot;
    }

    private void required(
            final @Nullable Text text, final String place, final int limit, final Map<String, Value> item) {
        if (text == null) {
            error("it lacks " + place);
        } else {
            text(text, place, limit, true, item);
        }
    }

    /** Records a placement and returns the slot when it is declared as {@code type}. */
    private @Nullable Slot place(final @Nullable String id, final Slot.Type type) {
        if (id == null) {
            error("a " + noun(type) + " names no slot");
            return null;
        }
        placed.merge(id, 1, Integer::sum);
        final Slot slot = declaration.slot(id).orElse(null);
        if (slot == null) {
            error("it places " + id + ", which the code does not fill");
            return null;
        }
        if (slot.type() != type) {
            error("it places the " + noun(slot.type()) + " " + id + " where a " + noun(type) + " belongs");
            return null;
        }
        return slot;
    }

    private void slots() {
        for (final Slot slot : declaration.slots()) {
            final int times = placed.getOrDefault(slot.id(), 0);
            if (times == 0 && slot.required()) {
                error("it does not place the " + noun(slot.type()) + " " + slot.id());
            } else if (times > 1 && slot.type() != Slot.Type.LIST) {
                error("it places the " + noun(slot.type()) + " " + slot.id() + " " + times + " times");
            }
        }
    }

    /** @param item the values of a data list's item or a menu's entry, which a condition may also name */
    private void condition(final @Nullable String when, final Map<String, Value> item) {
        if (when == null) {
            return;
        }
        final Condition condition = Condition.parse(when);
        if (condition == null) {
            error("the condition " + when + " is not value, !value or value=constant");
            return;
        }
        used.add(condition.value());
        final Value value = item.containsKey(condition.value())
                ? item.get(condition.value())
                : declaration.named().get(condition.value());
        if (value == null) {
            error("the condition " + when + " names {" + condition.value() + "}, which the document does not have");
        } else if (condition.constant() != null && !Kind.CHOICE.token().equals(value.kind())) {
            error("the condition " + when + " compares {" + condition.value() + "}, which is a " + value.kind()
                    + ", not a choice");
        }
    }

    private void text(
            final Text text,
            final String place,
            final int limit,
            final boolean oneLine,
            final Map<String, Value> item) {
        text(text, place, limit, oneLine, false, item);
    }

    /** Checks each language and version of a text as a message text in its place. */
    private void text(
            final Text text,
            final String place,
            final int limit,
            final boolean oneLine,
            final boolean markup,
            final Map<String, Value> item) {
        if (!text.languages().containsKey(Locales.DEFAULT_TAG)) {
            error(place + " has no English text, which every other language falls back to");
        }
        for (final String language : languages) {
            if (!language.equals(Locales.DEFAULT_TAG) && !text.languages().containsKey(language)) {
                error(place + " has no text in " + language + ", which the document's other texts have");
            }
        }
        final Declaration rules = declaration.text(item, limit, oneLine, markup);
        text.languages().forEach((language, versions) -> {
            if (!LANGUAGE.matcher(language).matches()) {
                error(place + " is in " + language + ", which is no language tag");
            }
            if (versions.isEmpty()) {
                error(place + " has no version in " + language);
            }
            for (final String version : versions) {
                final String where = place + " in " + language + " (" + version + ")";
                MessageCheck.check(version, rules, mode)
                        .forEach(problem -> problems.add(
                                new Problem(problem.error(), where + ": " + MessageCheck.english(problem.text()))));
                Typography.problems(version)
                        .forEach(rule ->
                                problems.add(new Problem(mode == MessageCheck.Mode.PACKAGED, where + ": " + rule)));
                names(version, markup);
            }
        });
    }

    private void names(final String version, final boolean markup) {
        try {
            nodes(MessageText.parse(version, markup).nodes());
        } catch (final MessageSyntaxException ignored) {
            // The message check reports it.
        }
    }

    private void nodes(final List<Node> nodes) {
        for (final Node node : nodes) {
            switch (node) {
                case Node.Value value -> used.add(value.name());
                case Node.Pound pound -> used.add(pound.name());
                case Node.Choice choice -> {
                    used.add(choice.name());
                    choice.cases().values().forEach(this::nodes);
                }
                case Node.Tag tag -> tag.args().forEach(arg -> nodes(arg.parts()));
                case Node.Literal ignored -> {}
            }
        }
    }

    /** A declared value is named by a text or a condition, a context by one of its attributes. */
    private void unnamed() {
        final Set<String> roles = new TreeSet<>();
        declaration
                .values()
                .keySet()
                .forEach(name -> roles.add(name.contains(".") ? name.substring(0, name.indexOf('.')) : name));
        for (final String role : roles) {
            if (!used.contains(role) && used.stream().noneMatch(name -> name.startsWith(role + "."))) {
                problems.add(new Problem(
                        mode == MessageCheck.Mode.PACKAGED, "it declares {" + role + "}, which no text names"));
            }
        }
    }

    /**
     * Measures the document at its largest: every conditional part shown and each data list filled once.
     * A block per language counts once per language of the file.
     */
    private void limits(final Document document) {
        final Measure measure = new Measure(declaration, languages);
        switch (declaration.shape()) {
            case MESSAGE -> {
                final int components = measure.components(document.parts());
                if (components > COMPONENTS) {
                    error("it draws " + components + " components, Discord draws " + COMPONENTS);
                }
                fits(measure, document, TEXT, "its text blocks");
            }
            case PLAIN -> fits(measure, document, PLAIN, "its content");
            // Each line of a name is checked against the limit by itself, and the renderer cuts the name it joins.
            case CHANNEL_NAME, MODAL -> {}
            // The game draws a menu and a game text whole, or cuts what does not fit its window.
            case MENU, CHAT, TAB_LIST, KICK_SCREEN, TITLE, BOSS_BAR, SERVER_LIST, ACTION_BAR, HOLOGRAM -> {}
        }
    }

    private void fits(final Measure measure, final Document document, final int limit, final String what) {
        for (final String language : languages) {
            final int length = measure.characters(document.parts(), language);
            if (length > limit) {
                error(what + " in " + language + " show " + length + " characters, Discord takes " + limit);
            }
        }
    }

    private static String noun(final Slot.Type type) {
        return switch (type) {
            case BUTTON -> "button";
            case SELECT -> "select";
            case LIST -> "data list";
            case LANGUAGES -> "block per language";
            case INPUT -> "field";
            case ITEM -> "item";
            case LABEL -> "label";
        };
    }

    private static String noun(final DocumentSpec.Shape shape) {
        return switch (shape) {
            case MESSAGE -> "message";
            case MODAL -> "modal";
            case PLAIN -> "plain message";
            case CHANNEL_NAME -> "channel name";
            case MENU -> "menu";
            case CHAT -> "chat text";
            case TAB_LIST -> "tab list";
            case KICK_SCREEN -> "kick screen";
            case TITLE -> "title";
            case BOSS_BAR -> "boss bar";
            case SERVER_LIST -> "server list";
            case ACTION_BAR -> "action bar";
            case HOLOGRAM -> "hologram";
        };
    }

    /** Measures a document at its largest. */
    private record Measure(DocumentDeclaration declaration, Set<String> languages) {

        int components(final List<Part> parts) {
            int count = 0;
            for (final Part part : parts) {
                count += switch (part.type()) {
                    case CONTAINER -> 1 + components(part.inner());
                    case SECTION -> 2 + components(part.inner());
                    case ROW -> 1 + part.allItems().size();
                    case LANGUAGES -> Math.max(1, languages.size()) * components(part.inner());
                    case TEXT, SEPARATOR, GALLERY, FILE, ITEM, LABEL -> 1;
                };
            }
            return count;
        }

        int characters(final List<Part> parts, final String language) {
            int count = 0;
            for (final Part part : parts) {
                count += switch (part.type()) {
                    case TEXT -> block(part, language);
                    case LANGUAGES -> {
                        int all = 0;
                        for (final String each : languages) {
                            all += characters(part.inner(), each);
                        }
                        yield all;
                    }
                    default -> characters(part.inner(), language);
                };
            }
            return count;
        }

        private int block(final Part part, final String language) {
            int count = 0;
            for (final Line line : part.allLines()) {
                final Slot slot = line.list() == null
                        ? null
                        : declaration.slot(line.list()).orElse(null);
                final Declaration rules = declaration.text(slot == null ? Map.of() : slot.values(), 0, false);
                int longest = 0;
                for (final String version : line.text().versions(language)) {
                    try {
                        longest = Math.max(longest, MessageCheck.shownLength(MessageText.parse(version, false), rules));
                    } catch (final MessageSyntaxException ignored) {
                        // The message check reports it.
                    }
                }
                count += longest + (line.style() == null ? 0 : line.style() == Line.Style.TITLE ? 3 : 4) + 1;
            }
            return Math.max(0, count - 1);
        }
    }
}
