package eu.nordtal.season.discordbot;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messages.document.Condition;
import eu.nordtal.season.messages.document.DocumentDeclaration;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Item;
import eu.nordtal.season.messages.document.Line;
import eu.nordtal.season.messages.document.Part;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.document.Text;
import eu.nordtal.season.messages.document.Value;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.value.Kind;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.label.LabelChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.section.SectionAccessoryComponent;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.jspecify.annotations.Nullable;

/** Draws a document, or the one an admin wrote over it, as Discord components, a modal or a plain text. */
public final class DocumentRenderer {

    static final int COMPONENTS = Message.MAX_COMPONENT_COUNT_IN_COMPONENT_TREE;

    static final int TEXT = Message.MAX_CONTENT_LENGTH_COMPONENT_V2;

    private static final int SECTION_TEXTS = Section.MAX_COMPONENTS;
    static final int ROW_BUTTONS = ActionRow.getMaxAllowed(Component.Type.BUTTON);
    private static final String ATTACHMENT = "attachment://";

    public sealed interface Filled {}

    public record FilledButton(String id, ButtonStyle style) implements Filled {

        public FilledButton(final String id) {
            this(id, ButtonStyle.PRIMARY);
        }

        /** A button that opens {@code url}. */
        public static FilledButton link(final String url) {
            return new FilledButton(url, ButtonStyle.LINK);
        }
    }

    public record FilledMembers(String id) implements Filled {}

    public record FilledInput(Function<@Nullable String, LabelChildComponent> field) implements Filled {}

    public record FilledSelect(String id, List<Option> options) implements Filled {

        public FilledSelect {
            options = List.copyOf(options);
        }
    }

    /**
     * @param label  shown where the select has no option line, else {@code null}
     * @param values the values of the select's option line
     * @param emoji  a Unicode emoji shown before the label, or {@code null}
     */
    public record Option(
            String value,
            @Nullable String label,
            Map<String, Object> values,
            @Nullable String emoji) {

        public Option {
            values = Map.copyOf(values);
        }

        /** An option that the select's option line shows with {@code values}. */
        public Option(final String value, final Record values, final @Nullable String emoji) {
            this(value, null, Value.read(values), emoji);
        }
    }

    /** Leaves an optional slot out. */
    public record FilledNothing() implements Filled {}

    public record FilledList(List<Map<String, Object>> items) implements Filled {

        public FilledList {
            items = List.copyOf(items);
        }

        public static FilledList of(final List<? extends Record> items) {
            return new FilledList(items.stream().map(Value::read).toList());
        }
    }

    public record FilledLanguages(List<Locale> languages) implements Filled {

        public FilledLanguages {
            languages = List.copyOf(languages);
        }
    }

    /**
     * @param attachments the names of the files that its {@code attachment://} images name. The message
     *                    uploads them.
     * @param uploads     the stored images that its {@code upload://} images show. The message uploads
     *                    them too.
     */
    public record Drawn(List<MessageTopLevelComponent> components, List<String> attachments, List<FileUpload> uploads) {

        public MessageCreateData create() {
            return new MessageCreateBuilder()
                    .useComponentsV2()
                    .setComponents(components)
                    .setFiles(files())
                    .build();
        }

        public MessageEditData edit() {
            return new MessageEditBuilder()
                    .setEmbeds(List.of())
                    .useComponentsV2()
                    .setComponents(components)
                    .setFiles(files())
                    .build();
        }

        public String text() {
            return MessageComponentTree.of(components).findAll(TextDisplay.class).stream()
                    .map(TextDisplay::getContent)
                    .collect(Collectors.joining("\n"));
        }

        private List<FileUpload> files() {
            final List<FileUpload> all =
                    new ArrayList<>(attachments.stream().map(Drawn::banner).toList());
            all.addAll(uploads);
            return all;
        }

        private static FileUpload banner(final String name) {
            final InputStream stream = DocumentRenderer.class.getClassLoader().getResourceAsStream("banners/" + name);
            if (stream == null) {
                throw new IllegalStateException("banners/" + name + " is missing from the jar");
            }
            return FileUpload.fromData(stream, name);
        }
    }

    /** A document that the code fills with nothing but its values, such as a reply or a direct message. */
    public record Reply<V extends Record>(DocumentSpec<V> document, V values) {

        public static Reply<DocumentSpec.None> of(final DocumentSpec<DocumentSpec.None> document) {
            return new Reply<>(document, new DocumentSpec.None());
        }
    }

    private final DiscordRenderer messages;
    private final StoredDocuments stored;

    public DocumentRenderer(final DiscordRenderer messages, final StoredDocuments stored) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.stored = Objects.requireNonNull(stored, "stored");
    }

    public DiscordRenderer messages() {
        return messages;
    }

    public Drawn reply(final Locale locale, final Reply<?> reply) {
        return draw(reply, locale);
    }

    public Drawn reply(final Locale locale, final DocumentSpec<DocumentSpec.None> spec) {
        return reply(locale, Reply.of(spec));
    }

    private <V extends Record> Drawn draw(final Reply<V> reply, final Locale locale) {
        return render(reply.document(), reply.values(), Map.of(), locale);
    }

    /**
     * Draws a message document.
     *
     * @throws IllegalArgumentException if the filling misses a required slot or fills one as another kind
     */
    public <V extends Record> Drawn render(
            final DocumentSpec<V> spec, final V values, final Map<String, Filled> slots, final Locale locale) {
        return draw(stored.applied(spec), Value.read(values), slots, locale);
    }

    /**
     * Draws a modal: its title, then each field under its label.
     *
     * @throws IllegalArgumentException if the filling misses a field
     */
    public <V extends Record> Modal modal(
            final DocumentSpec<V> spec,
            final String id,
            final V values,
            final Map<String, Filled> fields,
            final Locale locale) {
        final Pass pass = new Pass(stored.applied(spec), Value.read(values), fields, Map.of(), Map.of(), Map.of());
        final List<Part> parts = pass.document.parts();
        final List<Label> labels = new ArrayList<>();
        for (final Part row : parts.subList(1, parts.size())) {
            final Item field = row.allItems().getFirst();
            final Text placeholder = field.placeholder();
            labels.add(Label.of(
                    cut(
                            pass.plain(locale, Objects.requireNonNull(field.label(), "a field's label")),
                            Label.LABEL_MAX_LENGTH),
                    filled(fields, Objects.requireNonNull(field.slot(), "a field's slot"), FilledInput.class)
                            .field()
                            .apply(placeholder == null ? null : pass.plain(locale, placeholder))));
        }
        final String title =
                pass.plain(locale, parts.getFirst().allLines().getFirst().text());
        return Modal.create(id, cut(title, Modal.MAX_TITLE_LENGTH))
                .addComponents(labels)
                .build();
    }

    /** Returns a plain document's text, each shown line on a line of its own, or a channel's name. */
    public <V extends Record> String plain(final DocumentSpec<V> spec, final V values, final Locale locale) {
        final Pass pass = new Pass(stored.applied(spec), Value.read(values), Map.of(), Map.of(), Map.of(), Map.of());
        final boolean name = spec.shape() == DocumentSpec.Shape.CHANNEL_NAME;
        final List<String> lines = new ArrayList<>();
        for (final Part part : pass.document.parts()) {
            if (pass.holds(part.when(), Map.of())) {
                for (final Line line : pass.shown(part.allLines(), Map.of())) {
                    lines.add(name ? pass.plain(locale, line.text()) : pass.line(locale, line));
                }
            }
        }
        return name
                ? cut(String.join(" ", lines), Channel.MAX_NAME_LENGTH)
                : cut(String.join("\n", lines), Message.MAX_CONTENT_LENGTH);
    }

    /** Draws a packaged declaration and document. Every other way of drawing a message ends here. */
    Drawn draw(
            final Documents.Packaged packaged,
            final Map<String, Object> values,
            final Map<String, Filled> slots,
            final Locale locale) {
        final Map<String, FileUpload> uploads = new LinkedHashMap<>();
        for (final Part part : packaged.document().allParts()) {
            for (final String address : part.allMedia()) {
                stored.upload(address).ifPresent(upload -> uploads.putIfAbsent(address, upload));
            }
            if (part.thumbnail() != null) {
                stored.upload(part.thumbnail()).ifPresent(upload -> uploads.putIfAbsent(part.thumbnail(), upload));
            }
        }
        final Map<String, Integer> items = new HashMap<>();
        final Map<String, Integer> languages = new HashMap<>();
        for (final Slot slot : packaged.declaration().slots()) {
            if (!slot.required() && !slots.containsKey(slot.id())) {
                continue;
            }
            switch (slot.type()) {
                case LIST ->
                    items.put(
                            slot.id(),
                            filled(slots, slot.id(), FilledList.class).items().size());
                case LANGUAGES ->
                    languages.put(
                            slot.id(),
                            filled(slots, slot.id(), FilledLanguages.class)
                                    .languages()
                                    .size());
                case BUTTON, SELECT, INPUT -> {}
            }
        }
        while (true) {
            final Pass pass = new Pass(packaged, values, slots, items, languages, uploads);
            final List<MessageTopLevelComponent> drawn = pass.top(locale);
            if (pass.components > COMPONENTS && fewer(languages)) {
                continue;
            }
            if (pass.wanted > TEXT && fewer(items)) {
                continue;
            }
            return new Drawn(pass.fit(drawn), attachments(packaged), List.copyOf(uploads.values()));
        }
    }

    /** Takes one from the largest count above zero. Returns {@code false} when every count is zero. */
    private static boolean fewer(final Map<String, Integer> shown) {
        return shown.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(entry -> {
                    entry.setValue(entry.getValue() - 1);
                    return true;
                })
                .orElse(false);
    }

    private static List<String> attachments(final Documents.Packaged packaged) {
        return packaged.document().allParts().stream()
                .flatMap(part -> part.allMedia().stream())
                .filter(address -> address.startsWith(ATTACHMENT))
                .map(address -> address.substring(ATTACHMENT.length()))
                .distinct()
                .toList();
    }

    private static <T extends Filled> T filled(final Map<String, Filled> slots, final String id, final Class<T> kind) {
        final Filled filled = slots.get(id);
        if (!kind.isInstance(filled)) {
            throw new IllegalArgumentException(
                    "the slot " + id + " is filled as " + filled + ", not as a " + kind.getSimpleName());
        }
        return kind.cast(filled);
    }

    private final class Pass {

        private final DocumentDeclaration declaration;
        private final eu.nordtal.season.messages.document.Document document;
        private final Map<String, Object> values;
        private final Map<String, Filled> slots;
        private final Map<String, Integer> items;
        private final Map<String, Integer> languages;
        private final Map<String, FileUpload> uploads;
        private final Map<String, Kind> kinds;
        private final List<Integer> counts = new ArrayList<>();
        private int components;
        private int wanted;
        private int left = TEXT;

        Pass(
                final Documents.Packaged packaged,
                final Map<String, Object> values,
                final Map<String, Filled> slots,
                final Map<String, Integer> items,
                final Map<String, Integer> languages,
                final Map<String, FileUpload> uploads) {
            this.declaration = packaged.declaration();
            this.document = packaged.document();
            this.values = new HashMap<>(values);
            this.slots = slots;
            this.items = items;
            this.languages = languages;
            this.uploads = uploads;
            this.kinds = declaration.kinds(Map.of());
        }

        List<MessageTopLevelComponent> top(final Locale locale) {
            labels(locale);
            final List<MessageTopLevelComponent> drawn = new ArrayList<>();
            for (final Part part : document.parts()) {
                if (empty(part)) {
                    continue;
                }
                if (part.type() == Part.Type.LANGUAGES) {
                    for (final Locale language : languagesOf(part)) {
                        labels(language);
                        for (final Part inner : part.inner()) {
                            if (!empty(inner)) {
                                topOne(inner, language, Locales.tag(language), drawn);
                            }
                        }
                    }
                    labels(locale);
                } else {
                    topOne(part, locale, null, drawn);
                }
            }
            return drawn;
        }

        List<MessageTopLevelComponent> fit(final List<MessageTopLevelComponent> drawn) {
            int total = 0;
            int kept = 0;
            while (kept < drawn.size() && total + counts.get(kept) <= COMPONENTS) {
                total += counts.get(kept);
                kept++;
            }
            return List.copyOf(drawn.subList(0, kept));
        }

        /** Sets each button's label as the value {@code <slot>.label}, so a text names a button as it is shown. */
        private void labels(final Locale locale) {
            for (final Item item : document.items()) {
                if (item.slot() != null && item.label() != null) {
                    values.put(item.slot() + ".label", plain(locale, item.label()));
                }
            }
        }

        private void topOne(
                final Part part,
                final Locale locale,
                final @Nullable String tag,
                final List<MessageTopLevelComponent> into) {
            final int before = components;
            into.add(
                    switch (part.type()) {
                        case CONTAINER -> container(part, locale, tag);
                        case ROW -> row(part, locale, tag);
                        default -> (MessageTopLevelComponent) child(part, locale, tag);
                    });
            counts.add(components - before);
        }

        private List<Locale> languagesOf(final Part part) {
            final String id = Objects.requireNonNull(part.slot(), "a block per language names no slot");
            final List<Locale> all = filled(slots, id, FilledLanguages.class).languages();
            return all.subList(0, Math.min(all.size(), languages.getOrDefault(id, all.size())));
        }

        private Container container(final Part part, final Locale locale, final @Nullable String tag) {
            components++;
            final List<ContainerChildComponent> children = new ArrayList<>();
            for (final Part inner : part.inner()) {
                if (!empty(inner)) {
                    children.add(inner.type() == Part.Type.ROW ? row(inner, locale, tag) : child(inner, locale, tag));
                }
            }
            final Container container = Container.of(children);
            return part.accent() == null
                    ? container
                    : container.withAccentColor(Integer.parseInt(part.accent().substring(1), 16));
        }

        private ContainerChildComponent child(final Part part, final Locale locale, final @Nullable String tag) {
            return switch (part.type()) {
                case TEXT -> text(part, locale);
                case SECTION -> section(part, locale, tag);
                case SEPARATOR -> {
                    components++;
                    yield Separator.createDivider(Separator.Spacing.SMALL);
                }
                case GALLERY -> {
                    components++;
                    yield MediaGallery.of(part.allMedia().stream()
                            .limit(MediaGallery.MAX_ITEMS)
                            .map(this::url)
                            .map(MediaGalleryItem::fromUrl)
                            .toList());
                }
                case CONTAINER, ROW, LANGUAGES ->
                    throw new IllegalArgumentException("a " + part.type() + " cannot stand here");
            };
        }

        private Section section(final Part part, final Locale locale, final @Nullable String tag) {
            components++;
            final List<TextDisplay> texts = part.inner().stream()
                    .filter(inner -> !empty(inner))
                    .limit(SECTION_TEXTS)
                    .map(inner -> text(inner, locale))
                    .toList();
            final Item button = part.button();
            final SectionAccessoryComponent accessory;
            if (button != null) {
                accessory = button(button, locale, tag);
            } else {
                components++;
                accessory = Thumbnail.fromUrl(
                        url(Objects.requireNonNull(part.thumbnail(), "a section has neither button nor thumbnail")));
            }
            return Section.of(accessory, texts);
        }

        private ActionRow row(final Part part, final Locale locale, final @Nullable String tag) {
            components++;
            final List<ActionRowChildComponent> children = new ArrayList<>();
            for (final Item item : shownItems(part).stream().limit(ROW_BUTTONS).toList()) {
                children.add(
                        item.slot() == null
                                ? button(item, locale, tag)
                                : switch (slots.get(item.slot())) {
                                    case FilledSelect _ -> select(item, locale);
                                    case FilledMembers members -> members(item, members, locale);
                                    case null, default -> button(item, locale, tag);
                                });
            }
            return ActionRow.of(children);
        }

        private List<Item> shownItems(final Part part) {
            return part.allItems().stream()
                    .filter(item -> holds(item.when(), Map.of()))
                    .filter(item -> item.slot() == null
                            || !(slots.get(item.slot()) instanceof FilledNothing || absent(item.slot())))
                    .toList();
        }

        /** Returns whether an optional slot is not filled. */
        private boolean absent(final String id) {
            return !slots.containsKey(id)
                    && declaration.slot(id).map(slot -> !slot.required()).orElse(false);
        }

        /** Discord refuses a box or a section with nothing to draw. */
        private boolean empty(final Part part) {
            if (!holds(part.when(), Map.of())) {
                return true;
            }
            return switch (part.type()) {
                case ROW -> shownItems(part).isEmpty();
                case TEXT -> {
                    final List<Line> lines = shown(part.allLines(), Map.of());
                    yield lines.isEmpty()
                            || (lines.stream().anyMatch(line -> line.list() != null)
                                    && lines.stream()
                                            .allMatch(line -> line.list() != null
                                                    ? listed(line.list()).isEmpty()
                                                    : line.style() != null));
                }
                case CONTAINER, SECTION -> part.inner().stream().allMatch(this::empty);
                case LANGUAGES -> part.slot() != null && absent(part.slot());
                default -> false;
            };
        }

        private Button button(final Item item, final Locale locale, final @Nullable String tag) {
            components++;
            final String label = cut(plain(locale, Objects.requireNonNull(item.label(), "a button's label")), 80);
            if (item.slot() == null) {
                return Button.link(Objects.requireNonNull(item.link(), "a link button opens no address"), label);
            }
            final FilledButton filled = filled(slots, item.slot(), FilledButton.class);
            return filled.style() == ButtonStyle.LINK
                    ? Button.link(filled.id(), label)
                    : Button.of(filled.style(), filled.id() + (tag == null ? "" : tag), label);
        }

        private EntitySelectMenu members(final Item item, final FilledMembers filled, final Locale locale) {
            components++;
            return EntitySelectMenu.create(filled.id(), EntitySelectMenu.SelectTarget.USER)
                    .setPlaceholder(cut(placeholder(item, locale), SelectMenu.PLACEHOLDER_MAX_LENGTH))
                    .build();
        }

        private StringSelectMenu select(final Item item, final Locale locale) {
            components++;
            final String id = Objects.requireNonNull(item.slot());
            final Map<String, Kind> optionKinds =
                    declaration.kinds(declaration.requireSlot(id).values());
            final FilledSelect filled = filled(slots, id, FilledSelect.class);
            final List<SelectOption> options = new ArrayList<>();
            for (final Option option : filled.options().stream()
                    .limit(SelectMenu.OPTIONS_MAX_AMOUNT)
                    .toList()) {
                final Text line = item.option();
                final String label = line != null
                        ? format(locale, line, option.values(), optionKinds, TextFormat.PLAIN)
                        : Objects.requireNonNull(option.label(), "an option without a line of its own has no label");
                final SelectOption shown = SelectOption.of(cut(label, SelectOption.LABEL_MAX_LENGTH), option.value());
                options.add(option.emoji() == null ? shown : shown.withEmoji(Emoji.fromUnicode(option.emoji())));
            }
            return StringSelectMenu.create(filled.id())
                    .setPlaceholder(cut(placeholder(item, locale), SelectMenu.PLACEHOLDER_MAX_LENGTH))
                    .addOptions(options)
                    .build();
        }

        private String placeholder(final Item item, final Locale locale) {
            return plain(locale, Objects.requireNonNull(item.placeholder(), "a select's placeholder"));
        }

        private TextDisplay text(final Part part, final Locale locale) {
            components++;
            final List<String> lines = new ArrayList<>();
            for (final Line line : shown(part.allLines(), Map.of())) {
                if (line.list() != null) {
                    lines.addAll(list(line, locale));
                } else {
                    lines.add(line(locale, line));
                }
            }
            final String content = String.join("\n", lines);
            wanted += content.length();
            final String shown = cut(content, Math.max(1, left));
            left = Math.max(0, left - shown.length());
            return TextDisplay.of(shown);
        }

        String line(final Locale locale, final Line line) {
            final String text = format(locale, line.text(), Map.of(), kinds, TextFormat.DISCORD_MARKDOWN);
            return line.style() == null
                    ? text
                    : switch (line.style()) {
                        case TITLE -> "## " + text;
                        case HEADING -> "### " + text;
                    };
        }

        private List<String> list(final Line line, final Locale locale) {
            final String id = Objects.requireNonNull(line.list());
            final List<Map<String, Object>> all = listed(id);
            final int shown = Math.min(all.size(), items.getOrDefault(id, all.size()));
            final Map<String, Kind> itemKinds =
                    declaration.kinds(declaration.requireSlot(id).values());
            final List<String> lines = new ArrayList<>();
            for (final Map<String, Object> item : all.subList(0, shown)) {
                lines.add(format(locale, line.text(), item, itemKinds, TextFormat.DISCORD_MARKDOWN));
            }
            if (shown < all.size()) {
                lines.add("+" + (all.size() - shown));
            }
            return lines;
        }

        private List<Map<String, Object>> listed(final String id) {
            return absent(id) ? List.of() : filled(slots, id, FilledList.class).items();
        }

        List<Line> shown(final List<Line> lines, final Map<String, Object> item) {
            return lines.stream().filter(line -> holds(line.when(), item)).toList();
        }

        boolean holds(final @Nullable String when, final Map<String, Object> item) {
            if (when == null) {
                return true;
            }
            final Map<String, Object> all = new HashMap<>(values);
            all.putAll(item);
            return Condition.holds(when, all);
        }

        private String url(final String address) {
            final FileUpload upload = uploads.get(address);
            return upload == null ? address : ATTACHMENT + upload.getName();
        }

        String plain(final Locale locale, final Text text) {
            return format(locale, text, Map.of(), kinds, TextFormat.PLAIN);
        }

        /** Picks one version of the text in the reader's language and fills it. */
        private String format(
                final Locale locale,
                final Text text,
                final Map<String, Object> own,
                final Map<String, Kind> ownKinds,
                final TextFormat format) {
            final Map<String, Object> all = new LinkedHashMap<>(values);
            all.putAll(own);
            return messages.format(
                    locale,
                    text.pick(Locales.tag(locale), ThreadLocalRandom.current()),
                    all,
                    ownKinds,
                    format,
                    declaration.id());
        }
    }

    private static String cut(final String text, final int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
