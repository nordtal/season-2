package eu.nordtal.season.messages.spec.layout;

import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.messages.value.Kind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Holds layouts to their elements, to the specs of the keys they place and to what Discord draws.
 * Each problem is one English line, prefixed with the layout as {@code bundle/name}.
 */
public final class LayoutCheck {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern ACCENT = Pattern.compile("#[0-9a-f]{6}");
    private static final Pattern MEDIA = Pattern.compile("(attachment://[A-Za-z0-9_.-]+|https://\\S+)");

    /** What Components V2 allows: text blocks in a section, images in a gallery, buttons in a row. */
    private static final int SECTION_TEXTS = 3;

    private static final int GALLERY_IMAGES = 10;
    private static final int ROW_BUTTONS = 5;

    /** Where a plain line may be shown while Display still names embeds. */
    private static final List<Display> TEXT = List.of(Display.DISCORD_MESSAGE, Display.DISCORD_EMBED);

    private LayoutCheck() {}

    /** Returns one line per problem, empty when every layout is sound. */
    public static List<String> problems(
            final List<Layout> layouts, final Function<String, Optional<MessageSchema.Bundle>> schemas) {
        final List<String> problems = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final Layout layout : layouts) {
            final String id = layout.bundle() + "/" + layout.name();
            problems(layout, schemas).forEach(problem -> problems.add(id + ": " + problem));
            if (!seen.add(id)) {
                problems.add(id + ": declared more than once");
            }
        }
        return problems;
    }

    private static List<String> problems(
            final Layout layout, final Function<String, Optional<MessageSchema.Bundle>> schemas) {
        final List<String> problems = new ArrayList<>();
        if (!NAME.matcher(layout.name()).matches()) {
            problems.add("its name is not in kebab case");
        }
        structure(layout.parts(), false, false, problems);
        elements(layout, problems);
        for (final Placed placed : placements(layout)) {
            key(placed, schemas, problems);
        }
        LayoutSchema.describe(layout, schemas, problems::add);
        return problems;
    }

    private static void structure(
            final List<Part> parts, final boolean inContainer, final boolean inLanguages, final List<String> problems) {
        for (final Part part : parts) {
            final List<Part> inner = Objects.requireNonNullElse(part.parts(), List.of());
            final List<String> media = Objects.requireNonNullElse(part.media(), List.of());
            shape(part, inner, media, inContainer, inLanguages, problems);
            media.stream()
                    .filter(address -> !MEDIA.matcher(address).matches())
                    .forEach(address ->
                            problems.add("an image is at " + address + ", neither attachment:// nor https://"));
            structure(
                    inner,
                    inContainer || part.type() == Part.Type.CONTAINER,
                    inLanguages || part.type() == Part.Type.LANGUAGES,
                    problems);
        }
    }

    /** What Discord draws of one part of its type, apart from what it holds. */
    private static void shape(
            final Part part,
            final List<Part> inner,
            final List<String> media,
            final boolean inContainer,
            final boolean inLanguages,
            final List<String> problems) {
        switch (part.type()) {
            case CONTAINER -> {
                if (inContainer) {
                    problems.add("a container holds another container");
                }
                if (part.accent() != null && !ACCENT.matcher(part.accent()).matches()) {
                    problems.add("a container's accent " + part.accent() + " is not #rrggbb");
                }
            }
            case TEXT -> {
                final List<Line> lines = Objects.requireNonNullElse(part.lines(), List.of());
                if (lines.isEmpty()) {
                    problems.add("a text block has no lines");
                }
                if (lines.stream().anyMatch(line -> (line.key() == null) == (line.list() == null))) {
                    problems.add("a line is not either a key or a data list");
                }
            }
            case SECTION -> {
                if (inner.isEmpty() || inner.size() > SECTION_TEXTS) {
                    problems.add(
                            "a section holds " + inner.size() + " text blocks, Discord draws 1 to " + SECTION_TEXTS);
                }
                if (inner.stream().anyMatch(text -> text.type() != Part.Type.TEXT)) {
                    problems.add("a section holds something other than text blocks");
                }
                if ((part.element() == null) == (media.size() != 1)) {
                    problems.add("a section has not either a button or one thumbnail beside it");
                }
            }
            case GALLERY -> {
                if (media.isEmpty() || media.size() > GALLERY_IMAGES) {
                    problems.add("a gallery holds " + media.size() + " images, Discord draws 1 to " + GALLERY_IMAGES);
                }
            }
            case ROW -> {
                final int size =
                        Objects.requireNonNullElse(part.elements(), List.of()).size();
                if (size == 0 || size > ROW_BUTTONS) {
                    problems.add("a row holds " + size + " elements, Discord draws 1 to " + ROW_BUTTONS);
                }
            }
            case LANGUAGES -> {
                if (inLanguages) {
                    problems.add("a block per language holds another");
                }
            }
            case SEPARATOR -> {}
        }
    }

    /** Each declared element placed once, where its kind belongs; each placed one declared. */
    private static void elements(final Layout layout, final List<String> problems) {
        final Map<String, Element> declared = new LinkedHashMap<>();
        for (final Element element : layout.elements()) {
            if (declared.putIfAbsent(element.id(), element) != null) {
                problems.add("declares its element " + element.id() + " twice");
            }
            final int keys = element.keys().size();
            final boolean fits = switch (element.kind()) {
                case BUTTON, LIST -> keys == 1;
                case SELECT -> keys == 1 || keys == 2;
                case LANGUAGES -> keys == 0;
            };
            if (!fits) {
                problems.add("its " + noun(element.kind()) + " " + element.id() + " draws " + keys + " texts");
            }
        }
        final Map<String, Integer> placed = new LinkedHashMap<>();
        for (final Part part : layout.allParts()) {
            final List<String> row = Objects.requireNonNullElse(part.elements(), List.of());
            for (final String id : row) {
                final Element element = declared.get(id);
                if (element == null || element.kind() != Element.Kind.SELECT) {
                    place(id, Element.Kind.BUTTON, declared, placed, problems);
                    continue;
                }
                if (row.size() > 1) {
                    problems.add("a row holds a select beside something else");
                }
                placed.merge(id, 1, Integer::sum);
            }
            if (part.type() == Part.Type.SECTION && part.element() != null) {
                place(part.element(), Element.Kind.BUTTON, declared, placed, problems);
            }
            if (part.type() == Part.Type.LANGUAGES && part.element() != null) {
                place(part.element(), Element.Kind.LANGUAGES, declared, placed, problems);
            }
            for (final Line line : Objects.requireNonNullElse(part.lines(), List.<Line>of())) {
                if (line.list() != null) {
                    place(line.list(), Element.Kind.LIST, declared, placed, problems);
                }
            }
        }
        for (final String id : declared.keySet()) {
            final int times = placed.getOrDefault(id, 0);
            if (times == 0) {
                problems.add("does not place its element " + id);
            } else if (times > 1) {
                problems.add("places its element " + id + " " + times + " times");
            }
        }
    }

    private static void place(
            final String id,
            final Element.Kind where,
            final Map<String, Element> declared,
            final Map<String, Integer> placed,
            final List<String> problems) {
        final Element element = declared.get(id);
        if (element == null) {
            problems.add("places " + id + ", which it does not declare");
            return;
        }
        if (element.kind() != where) {
            problems.add("places the " + noun(element.kind()) + " " + id + " where a " + noun(where) + " belongs");
        }
        placed.merge(id, 1, Integer::sum);
    }

    private static String noun(final Element.Kind kind) {
        return switch (kind) {
            case BUTTON -> "button";
            case SELECT -> "select";
            case LIST -> "data list";
            case LANGUAGES -> "block per language";
        };
    }

    /**
     * A key where a layout places it.
     *
     * @param as     how the problem names the place
     * @param places the places of its {@code @Shown} of which one must be named
     */
    private record Placed(String key, String as, List<Display> places) {}

    /** Every key of the layout's text blocks, then of its elements, where it is placed. */
    private static List<Placed> placements(final Layout layout) {
        final List<Placed> placed = new ArrayList<>();
        for (final Part part : layout.allParts()) {
            for (final Line line : Objects.requireNonNullElse(part.lines(), List.<Line>of())) {
                if (line.key() != null) {
                    placed.add(
                            line.style() == null
                                    ? new Placed(line.key(), "text", TEXT)
                                    : new Placed(
                                            line.key(), "a title or heading", List.of(Display.DISCORD_EMBED_HEADING)));
                }
            }
        }
        for (final Element element : layout.elements()) {
            for (final String key : element.keys()) {
                placed.add(
                        switch (element.kind()) {
                            case BUTTON -> new Placed(key, "a button's label", List.of(Display.DISCORD_BUTTON));
                            case SELECT -> new Placed(key, "a select's text", List.of(Display.DISCORD_SELECT));
                            case LIST, LANGUAGES -> new Placed(key, "a data list's line", TEXT);
                        });
            }
        }
        return placed;
    }

    private static void key(
            final Placed placed,
            final Function<String, Optional<MessageSchema.Bundle>> schemas,
            final List<String> problems) {
        final Matcher matcher = LayoutSchema.KEY.matcher(placed.key());
        if (!matcher.matches()) {
            problems.add("names " + placed.key() + ", which is no bundle/key");
            return;
        }
        if (schemas.apply(matcher.group(1)).isEmpty()) {
            problems.add("names " + placed.key() + ", but no bundle " + matcher.group(1) + " is on the classpath");
            return;
        }
        final Optional<LayoutSchema.Found> found = LayoutSchema.find(placed.key(), schemas);
        if (found.isEmpty()) {
            problems.add("names " + placed.key() + ", which its bundle does not declare");
            return;
        }
        final List<Display> shown = found.get().entry().shown();
        if (shown.stream().noneMatch(placed.places()::contains)) {
            problems.add("places " + placed.key() + " as " + placed.as() + ", but its @Shown names only "
                    + shown.stream().map(Enum::name).collect(Collectors.joining(", ")));
        }
        for (final MessageSchema.Arg arg : found.get().entry().args()) {
            if (Kind.MESSAGE.token().equals(arg.kind()) && arg.takes() == null) {
                problems.add("places " + placed.key() + ", whose message value " + arg.name()
                        + " names no keys with @Takes");
            }
        }
    }
}
