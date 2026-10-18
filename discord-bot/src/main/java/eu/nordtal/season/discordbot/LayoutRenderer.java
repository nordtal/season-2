package eu.nordtal.season.discordbot;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
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
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.jspecify.annotations.Nullable;

/**
 * Draws a Discord layout, or the rebuild stored over it, as a Components V2 message with what the code fills in.
 * It keeps to Discord's limits: a data list loses its last items to a "+N" line, a block per language its last
 * languages, and whatever still does not fit is cut.
 */
public final class LayoutRenderer {

    /** Discord counts every component of a message, nested ones included. */
    static final int COMPONENTS = Message.MAX_COMPONENT_COUNT_IN_COMPONENT_TREE;

    /** The characters of all text blocks of one message together. */
    static final int TEXT = Message.MAX_CONTENT_LENGTH_COMPONENT_V2;

    private static final int SECTION_TEXTS = Section.MAX_COMPONENTS;
    private static final int ROW_BUTTONS = 5;
    private static final String ATTACHMENT = "attachment://";

    /**
     * What the code fills into a layout.
     *
     * @param values   the values its texts name
     * @param elements each declared element by id
     */
    public record Filling(Map<String, Object> values, Map<String, Filled> elements) {

        public Filling {
            values = Map.copyOf(values);
            elements = Map.copyOf(elements);
        }

        /** A filling whose values are those the spec gives {@code texts}, each of the kind its text declares. */
        public static Filling of(final Map<String, Filled> elements, final MessageRef... texts) {
            final Map<String, Object> values = new HashMap<>();
            for (final MessageRef text : texts) {
                values.putAll(text.args());
            }
            return new Filling(values, elements);
        }
    }

    /** One element as the code fills it. */
    public sealed interface Filled {}

    /** A button's custom id and colour; in a block per language the language's tag is appended to the id. */
    public record FilledButton(String id, ButtonStyle style) implements Filled {

        public FilledButton(final String id) {
            this(id, ButtonStyle.PRIMARY);
        }
    }

    /** A select of guild members by its custom id, the one select whose options Discord fills in. */
    public record FilledMembers(String id) implements Filled {}

    /** A modal's field, which the code builds given its placeholder, or {@code null} when it declares none. */
    public record FilledInput(Function<@Nullable String, LabelChildComponent> field) implements Filled {}

    /** A select's custom id and its options. */
    public record FilledSelect(String id, List<Option> options) implements Filled {

        public FilledSelect {
            options = List.copyOf(options);
        }
    }

    /**
     * One option of a select.
     *
     * @param label its label where the select declares no line for its options, else {@code null}
     * @param args  the values of the select's option line
     * @param emoji a Unicode emoji shown before it, or {@code null}
     */
    public record Option(
            String value,
            @Nullable String label,
            Map<String, Object> args,
            @Nullable String emoji) {

        public Option {
            args = Map.copyOf(args);
        }
    }

    /** A button or select the code leaves out this time; a row with nothing else in it is left out with it. */
    public record FilledNothing() implements Filled {}

    /** A data list's items, each the values of its line. */
    public record FilledList(List<Map<String, Object>> items) implements Filled {

        public FilledList {
            items = List.copyOf(items);
        }
    }

    /** The languages a block per language is drawn in, in order. */
    public record FilledLanguages(List<Locale> languages) implements Filled {

        public FilledLanguages {
            languages = List.copyOf(languages);
        }
    }

    /**
     * A drawn message.
     *
     * @param components  its top-level components
     * @param attachments the names of the files its {@code attachment://} images name, which the message uploads
     * @param uploads     the stored images its {@code upload://} images show, which the message uploads too
     */
    public record Drawn(List<MessageTopLevelComponent> components, List<String> attachments, List<FileUpload> uploads) {

        /** It as a new message: a reply, a direct message or a post. */
        public MessageCreateData create() {
            return new MessageCreateBuilder()
                    .useComponentsV2()
                    .setComponents(components)
                    .setFiles(files())
                    .build();
        }

        /**
         * The edit that turns a message into it: Components V2 with its embeds emptied, as Discord requires.
         * The images are uploaded anew and replace the old ones, so a swapped banner shows.
         */
        public MessageEditData edit() {
            return new MessageEditBuilder()
                    .setEmbeds(List.of())
                    .useComponentsV2()
                    .setComponents(components)
                    .setFiles(files())
                    .build();
        }

        /** Its text blocks one under the other, as an alert quotes what a member was sent. */
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
            final InputStream stream = LayoutRenderer.class.getClassLoader().getResourceAsStream("banners/" + name);
            if (stream == null) {
                throw new IllegalStateException("banners/" + name + " is missing from the jar");
            }
            return FileUpload.fromData(stream, name);
        }
    }

    private final DiscordRenderer messages;
    private final StoredLayouts stored;

    public LayoutRenderer(final DiscordRenderer messages, final StoredLayouts stored) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.stored = Objects.requireNonNull(stored, "stored");
    }

    /** The texts it draws with. */
    public DiscordRenderer messages() {
        return messages;
    }

    /** Draws a reply of one text by the layout {@link Replies} declares for its key, filled with its values. */
    public Drawn reply(final Locale locale, final MessageRef text) {
        return render(Replies.of(text.key()), new Filling(text.args(), Map.of()), locale);
    }

    /**
     * Draws a modal, or the rebuild stored over it, in {@code locale}: its title, then each field under its label.
     *
     * @throws IllegalArgumentException if the filling misses a field
     */
    public Modal modal(final Layout declared, final String id, final Filling filling, final Locale locale) {
        final Layout layout = stored.applied(declared);
        final Pass pass = new Pass(layout, filling, Map.of(), Map.of(), Map.of());
        final Line title =
                Objects.requireNonNull(layout.parts().getFirst().lines()).getFirst();
        final List<Label> fields = new ArrayList<>();
        for (final Part row : layout.parts().subList(1, layout.parts().size())) {
            final Element field =
                    pass.element(Objects.requireNonNull(row.elements()).getFirst());
            final List<String> keys = field.keys();
            fields.add(Label.of(
                    cut(pass.format(locale, keys.getFirst(), Map.of()), Label.LABEL_MAX_LENGTH),
                    filled(filling, field.id(), FilledInput.class)
                            .field()
                            .apply(keys.size() > 1 ? pass.format(locale, keys.get(1), Map.of()) : null)));
        }
        return Modal.create(
                        id,
                        cut(
                                pass.format(locale, Objects.requireNonNull(title.key(), "a modal's title"), Map.of()),
                                Modal.MAX_TITLE_LENGTH))
                .addComponents(fields)
                .build();
    }

    /**
     * Draws {@code declared}, or the rebuild stored over it, in {@code locale}.
     *
     * @throws IllegalArgumentException if the filling misses an element or fills one as another kind
     */
    public Drawn render(final Layout declared, final Filling filling, final Locale locale) {
        final Layout layout = stored.applied(declared);
        final Map<String, FileUpload> uploads = new LinkedHashMap<>();
        for (final Part part : layout.allParts()) {
            for (final String address : Objects.requireNonNullElse(part.media(), List.<String>of())) {
                stored.upload(address).ifPresent(upload -> uploads.putIfAbsent(address, upload));
            }
        }
        final Map<String, Integer> items = new HashMap<>();
        final Map<String, Integer> languages = new HashMap<>();
        for (final Element element : layout.elements()) {
            switch (element.kind()) {
                case LIST ->
                    items.put(
                            element.id(),
                            filled(filling, element.id(), FilledList.class)
                                    .items()
                                    .size());
                case LANGUAGES ->
                    languages.put(
                            element.id(),
                            filled(filling, element.id(), FilledLanguages.class)
                                    .languages()
                                    .size());
                case BUTTON, SELECT, LINK, INPUT -> {}
            }
        }
        while (true) {
            final Pass pass = new Pass(layout, filling, items, languages, uploads);
            final List<MessageTopLevelComponent> drawn = pass.top(locale);
            if (pass.components > COMPONENTS && fewer(languages)) {
                continue;
            }
            if (pass.wanted > TEXT && fewer(items)) {
                continue;
            }
            return new Drawn(pass.fit(drawn), attachments(layout), List.copyOf(uploads.values()));
        }
    }

    /** Takes one from the largest count above zero; {@code false} when every count is zero. */
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

    private static List<String> attachments(final Layout layout) {
        return layout.allParts().stream()
                .flatMap(part -> Objects.requireNonNullElse(part.media(), List.<String>of()).stream())
                .filter(address -> address.startsWith(ATTACHMENT))
                .map(address -> address.substring(ATTACHMENT.length()))
                .distinct()
                .toList();
    }

    private static <T extends Filled> T filled(final Filling filling, final String id, final Class<T> kind) {
        final Filled filled = filling.elements().get(id);
        if (!kind.isInstance(filled)) {
            throw new IllegalArgumentException(
                    "the element " + id + " is filled as " + filled + ", not as a " + kind.getSimpleName());
        }
        return kind.cast(filled);
    }

    /** One drawing with a given number of items and languages, counting what it would send. */
    private final class Pass {

        private final Layout layout;
        private final Filling filling;
        private final Map<String, Integer> items;
        private final Map<String, Integer> languages;
        private final Map<String, FileUpload> uploads;
        private final List<Integer> counts = new ArrayList<>();
        private int components;
        private int wanted;
        private int left = TEXT;

        Pass(
                final Layout layout,
                final Filling filling,
                final Map<String, Integer> items,
                final Map<String, Integer> languages,
                final Map<String, FileUpload> uploads) {
            this.layout = layout;
            this.filling = filling;
            this.items = items;
            this.languages = languages;
            this.uploads = uploads;
        }

        List<MessageTopLevelComponent> top(final Locale locale) {
            final List<MessageTopLevelComponent> drawn = new ArrayList<>();
            for (final Part part : layout.parts()) {
                if (empty(part)) {
                    continue;
                }
                if (part.type() == Part.Type.LANGUAGES) {
                    for (final Locale language : languagesOf(part)) {
                        for (final Part inner : Objects.requireNonNullElse(part.parts(), List.<Part>of())) {
                            topOne(inner, language, Locales.tag(language), drawn);
                        }
                    }
                } else {
                    topOne(part, locale, null, drawn);
                }
            }
            return drawn;
        }

        /** Drops the last top-level components until the rest is within Discord's count. */
        List<MessageTopLevelComponent> fit(final List<MessageTopLevelComponent> drawn) {
            int total = 0;
            int kept = 0;
            while (kept < drawn.size() && total + counts.get(kept) <= COMPONENTS) {
                total += counts.get(kept);
                kept++;
            }
            return List.copyOf(drawn.subList(0, kept));
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
            final String id = Objects.requireNonNull(part.element(), "a block per language names no element");
            final List<Locale> all = filled(filling, id, FilledLanguages.class).languages();
            return all.subList(0, Math.min(all.size(), languages.getOrDefault(id, all.size())));
        }

        private Container container(final Part part, final Locale locale, final @Nullable String tag) {
            components++;
            final List<ContainerChildComponent> children = new ArrayList<>();
            for (final Part inner : Objects.requireNonNullElse(part.parts(), List.<Part>of())) {
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
                    yield MediaGallery.of(Objects.requireNonNullElse(part.media(), List.<String>of()).stream()
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
            final List<TextDisplay> texts = Objects.requireNonNullElse(part.parts(), List.<Part>of()).stream()
                    .filter(inner -> !empty(inner))
                    .limit(SECTION_TEXTS)
                    .map(inner -> text(inner, locale))
                    .toList();
            final SectionAccessoryComponent accessory;
            if (part.element() != null) {
                accessory = button(part.element(), locale, tag);
            } else {
                components++;
                accessory = Thumbnail.fromUrl(
                        url(Objects.requireNonNull(part.media(), "a section has neither button nor thumbnail")
                                .getFirst()));
            }
            return Section.of(accessory, texts);
        }

        private ActionRow row(final Part part, final Locale locale, final @Nullable String tag) {
            components++;
            final List<ActionRowChildComponent> children = new ArrayList<>();
            for (final String id : Objects.requireNonNullElse(part.elements(), List.<String>of()).stream()
                    .filter(id -> !(filling.elements().get(id) instanceof FilledNothing))
                    .limit(ROW_BUTTONS)
                    .toList()) {
                children.add(
                        switch (filling.elements().get(id)) {
                            case FilledSelect _ -> select(id, locale);
                            case FilledMembers members -> members(id, members, locale);
                            case null, default -> button(id, locale, tag);
                        });
            }
            return ActionRow.of(children);
        }

        /** Whether a part has nothing left to draw, which Discord refuses for a box or a section. */
        private boolean empty(final Part part) {
            return switch (part.type()) {
                case ROW ->
                    Objects.requireNonNullElse(part.elements(), List.<String>of()).stream()
                            .allMatch(id -> filling.elements().get(id) instanceof FilledNothing);
                case TEXT -> {
                    final List<Line> lines = Objects.requireNonNullElse(part.lines(), List.<Line>of());
                    yield lines.stream().anyMatch(line -> line.list() != null)
                            && lines.stream()
                                    .allMatch(line -> line.list() != null
                                            ? filled(filling, line.list(), FilledList.class)
                                                    .items()
                                                    .isEmpty()
                                            : line.style() != null);
                }
                case CONTAINER, SECTION ->
                    Objects.requireNonNullElse(part.parts(), List.<Part>of()).stream()
                            .allMatch(this::empty);
                default -> false;
            };
        }

        private Button button(final String id, final Locale locale, final @Nullable String tag) {
            components++;
            final Element element = element(id);
            if (element.kind() == Element.Kind.LINK) {
                return Button.link(
                        Objects.requireNonNull(element.url(), "a link button opens no address"),
                        cut(added(locale, Objects.requireNonNull(element.labels(), "a link button has no label")), 80));
            }
            final FilledButton filled = filled(filling, id, FilledButton.class);
            return Button.of(
                    filled.style(),
                    filled.id() + (tag == null ? "" : tag),
                    cut(format(locale, element.keys().getFirst(), Map.of()), 80));
        }

        private EntitySelectMenu members(final String id, final FilledMembers filled, final Locale locale) {
            components++;
            return EntitySelectMenu.create(filled.id(), EntitySelectMenu.SelectTarget.USER)
                    .setPlaceholder(cut(
                            format(locale, element(id).keys().getFirst(), Map.of()), SelectMenu.PLACEHOLDER_MAX_LENGTH))
                    .build();
        }

        private StringSelectMenu select(final String id, final Locale locale) {
            components++;
            final Element element = element(id);
            final List<SelectOption> options = new ArrayList<>();
            for (final Option option : filled(filling, id, FilledSelect.class).options().stream()
                    .limit(SelectMenu.OPTIONS_MAX_AMOUNT)
                    .toList()) {
                final String label = element.keys().size() > 1
                        ? format(locale, element.keys().get(1), option.args())
                        : Objects.requireNonNull(option.label(), "an option without a line of its own has no label");
                final SelectOption shown = SelectOption.of(cut(label, SelectOption.LABEL_MAX_LENGTH), option.value());
                options.add(option.emoji() == null ? shown : shown.withEmoji(Emoji.fromUnicode(option.emoji())));
            }
            return StringSelectMenu.create(
                            filled(filling, id, FilledSelect.class).id())
                    .setPlaceholder(
                            cut(format(locale, element.keys().getFirst(), Map.of()), SelectMenu.PLACEHOLDER_MAX_LENGTH))
                    .addOptions(options)
                    .build();
        }

        private TextDisplay text(final Part part, final Locale locale) {
            components++;
            final List<String> lines = new ArrayList<>();
            for (final Line line : Objects.requireNonNullElse(part.lines(), List.<Line>of())) {
                if (line.list() != null) {
                    lines.addAll(list(line.list(), locale));
                    continue;
                }
                final String text = line.texts() != null
                        ? added(locale, line.texts())
                        : format(locale, Objects.requireNonNull(line.key(), "a line names no key"), Map.of());
                lines.add(
                        line.style() == null
                                ? text
                                : switch (line.style()) {
                                    case TITLE -> "## " + text;
                                    case HEADING -> "### " + text;
                                });
            }
            final String content = String.join("\n", lines);
            wanted += content.length();
            final String shown = cut(content, Math.max(1, left));
            left = Math.max(0, left - shown.length());
            return TextDisplay.of(shown);
        }

        private List<String> list(final String id, final Locale locale) {
            final List<Map<String, Object>> all =
                    filled(filling, id, FilledList.class).items();
            final int shown = Math.min(all.size(), items.getOrDefault(id, all.size()));
            final String line = element(id).keys().getFirst();
            final List<String> lines = new ArrayList<>();
            all.subList(0, shown).forEach(item -> lines.add(format(locale, line, item)));
            if (shown < all.size()) {
                lines.add("+" + (all.size() - shown));
            }
            return lines;
        }

        private Element element(final String id) {
            return layout.elements().stream()
                    .filter(element -> element.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("the layout declares no element " + id));
        }

        /** The address Discord is sent for an image: a stored image as the attachment the message uploads. */
        private String url(final String address) {
            final FileUpload upload = uploads.get(address);
            return upload == null ? address : ATTACHMENT + upload.getName();
        }

        /**
         * An editor's added text in the reader's language, else in the default one, else in the first it has.
         * It is written and filled as the layout's first key, which shares its bundle's way of writing.
         */
        private String added(final Locale locale, final Map<String, String> texts) {
            final String text = texts.getOrDefault(
                    Locales.tag(locale),
                    texts.getOrDefault(
                            Locales.DEFAULT_TAG, texts.values().iterator().next()));
            final String key = layout.keys().getFirst();
            return messages.format(locale, new MessageRef(key.substring(key.indexOf('/') + 1), filling.values()), text);
        }

        /** A key's text, as {@code bundle/key}, filled with the layout's values and then {@code own}. */
        private String format(final Locale locale, final String key, final Map<String, Object> own) {
            final Map<String, Object> values = new LinkedHashMap<>(filling.values());
            values.putAll(own);
            return messages.format(locale, new MessageRef(key.substring(key.indexOf('/') + 1), values));
        }
    }

    private static String cut(final String text, final int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
