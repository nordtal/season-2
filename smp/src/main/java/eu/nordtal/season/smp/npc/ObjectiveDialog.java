package eu.nordtal.season.smp.npc;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.GameLines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.packrendering.ui.BodyColumn;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.CardGrid;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogCanvas;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.FocusRing;
import eu.nordtal.season.packrendering.ui.ItemPicture;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.ScrollState;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.packrendering.ui.Tooltip;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.smp.TakenItems;
import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneNames;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.milestone.ObjectiveType;
import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The spawn NPC's objectives dialog: milestone rail, projected spins plate, and two-column objective cards.
 * Shows a vertical scroll column on the right when more than four objectives are active.
 */
public final class ObjectiveDialog {

    record Count(
            @Example("120") long amount, @Example("256") long target) {}

    record OwnAmount(@Example("41") long amount) {}

    record ShareLine(
            @Example("2") long spins, @Example("20") long budget) {}

    record RewardHover(
            @Example("Mine deepslate diamond ore") String title,
            @Example("Finished") String detail,
            @Example("1") int amount,

            @Example("item.minecraft.buried_ancient_city_map")
            GameContent item,

            boolean hasHint,
            @Example("Hand in items") String hint) {}

    static final DocumentSpec<RewardHover> REWARD_HOVER = DocumentSpec.game(
                    "smp", "objective-reward-hover", "Menus", DocumentSpec.Shape.CHAT, RewardHover.class)
            .titled("Objective reward tooltip");

    static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "objectives",
                    "Menus",
                    DocumentSpec.None.class,
                    1,
                    Slot.label("finished"),
                    Slot.label("progress-hand-in", Count.class),
                    Slot.label("progress-counted", Count.class),
                    Slot.label("own-amount", OwnAmount.class),
                    Slot.label("own-done"),
                    Slot.label("hand-in-button"),
                    Slot.label("share-expected", ShareLine.class),
                    Slot.label("share-unlinked"),
                    Slot.label("scroll-up"),
                    Slot.label("scroll-down"),
                    Slot.label("not-started"),
                    Slot.label("none"),
                    Slot.label("all"))
            .titled("Objectives");

    /** Content width of the 280 by 161 panel (7 px margin on each side). */
    static final int WIDTH = DialogPanel.WIDE_161.contentWidth();

    /** Number of visible card rows at once (two columns by two rows = four cards). */
    static final int VISIBLE_ROWS = 2;

    /** Width of the projected spins plate at the right end of the milestone rail. */
    private static final int SPINS_WIDTH = 36;

    /** Width of the milestone rail left of the spins plate (7 px gap between them). */
    private static final int RAIL_WIDTH = WIDTH - SPINS_WIDTH - 7;

    /** Top of the two-column card grid and scroll column in content coordinates. */
    private static final int GRID_TOP = 24;

    /** Card height and vertical gap so two rows fill the 112 px grid area (54 + 4 + 54 = 112). */
    private static final int CARD_HEIGHT = 54;

    private static final int CARD_ROW_GAP = 4;

    /** Width of the hand-in button plate on an open hand-in objective card. */
    private static final int HAND_IN_PLATE_WIDTH = 62;

    private static final int TEXT_TOP = 50;

    /** Where the words of an empty list stand: as a text line's capitals would under the first body line's box. */
    private static final int EMPTY_TEXT = BodyColumn.BODY_TOP + 5;

    private final @Nullable MessageRenderer renderer;
    private final @Nullable AppliedDocuments documents;
    private final Dialogs dialogs;
    private final Locale locale;
    private final Milestone milestone;
    private final int totalMilestones;
    private final int currentMilestone;
    private final List<ObjectiveRow> rows;
    private final OwnShare.Summary share;
    private final BiConsumer<Player, HandInGui> confirm;
    private final Consumer<Runnable> nextTick;
    private final GameDocument document;
    private final Function<Objective, String> objectiveName;
    private final Function<NamespacedKey, @Nullable String> advancementIcon;
    private final boolean readOnly;

    public ObjectiveDialog(
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Dialogs dialogs,
            final Locale locale,
            final Milestone milestone,
            final int totalMilestones,
            final int currentMilestone,
            final List<ObjectiveRow> rows,
            final OwnShare.Summary share,
            final BiConsumer<Player, HandInGui> confirm,
            final Consumer<Runnable> nextTick) {
        this(
                renderer,
                documents,
                dialogs,
                locale,
                milestone,
                totalMilestones,
                currentMilestone,
                rows,
                share,
                confirm,
                nextTick,
                document(renderer, documents, locale),
                objective -> MilestoneNames.objective(renderer, locale, milestone.key(), objective.key()),
                ObjectiveDialog::advancementIconKey,
                false);
    }

    private ObjectiveDialog(
            final @Nullable MessageRenderer renderer,
            final @Nullable AppliedDocuments documents,
            final Dialogs dialogs,
            final Locale locale,
            final Milestone milestone,
            final int totalMilestones,
            final int currentMilestone,
            final List<ObjectiveRow> rows,
            final OwnShare.Summary share,
            final BiConsumer<Player, HandInGui> confirm,
            final Consumer<Runnable> nextTick,
            final GameDocument document,
            final Function<Objective, String> objectiveName,
            final Function<NamespacedKey, @Nullable String> advancementIcon,
            final boolean readOnly) {
        this.renderer = renderer;
        this.documents = documents;
        this.dialogs = dialogs;
        this.locale = locale;
        this.milestone = milestone;
        this.totalMilestones = totalMilestones;
        this.currentMilestone = currentMilestone;
        this.rows = List.copyOf(rows);
        this.share = share;
        this.confirm = confirm;
        this.nextTick = nextTick;
        this.document = document;
        this.objectiveName = objectiveName;
        this.advancementIcon = advancementIcon;
        this.readOnly = readOnly;
    }

    private static GameDocument document(
            final MessageRenderer renderer, final AppliedDocuments documents, final Locale locale) {
        return new GameDocument(renderer, documents.applied(DOCUMENT), Viewer.of(locale), new DocumentSpec.None());
    }

    /** Why the menu has no objectives to show. */
    public enum Empty {
        /** The first milestone, which has no objectives, is not unlocked yet. */
        NOT_STARTED("not-started"),
        /** No milestone is active, though some are left. */
        NONE("none"),
        /** Every milestone is finished. */
        ALL("all");

        private final String label;

        Empty(final String label) {
            this.label = label;
        }

        /**
         * Returns why the menu shows no objectives, or nothing when {@code active} has objectives to show.
         * A milestone without objectives waits for an admin unlock. The first one's unlock starts the season.
         */
        public static Optional<Empty> of(
                final MilestoneTrack track, final List<String> completed, final @Nullable Milestone active) {
            if (active == null) {
                if (track.size() > 0 && track.next(completed).isEmpty()) {
                    return Optional.of(ALL);
                }
                return Optional.of(completed.isEmpty() ? NOT_STARTED : NONE);
            }
            if (active.objectives().isEmpty()) {
                return Optional.of(completed.isEmpty() ? NOT_STARTED : NONE);
            }
            return Optional.empty();
        }
    }

    /** Shows that no objectives run, and why. */
    public static Dialog empty(
            final MessageRenderer renderer, final AppliedDocuments documents, final Locale locale, final Empty why) {
        final GameDocument document = document(renderer, documents, locale);
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(DialogTitle.of(DialogPanel.OBJECTIVES_33, TEXT_TOP)
                                .header(Symbol.FLAG, Dialogs.plain(document.title()), List.of())
                                .centred(EMPTY_TEXT, document.label(why.label), Colours.SOFT)
                                .build())
                        .build())
                .type(Dialogs.bodyOnly()));
    }

    /** Opens a read-only objectives dialog at its example state in {@code drafted}'s words for Steward's preview. */
    static void example(final GameDocument drafted, final Dialogs dialogs, final Locale locale, final Player reader) {
        final Milestone sampleMilestone = new Milestone(
                "example", eu.nordtal.season.smp.milestone.Unlock.NOTHING, List.of(), false, exampleObjectives());
        final boolean german = Locale.GERMAN.getLanguage().equals(locale.getLanguage());
        final Function<Objective, String> names = objective -> exampleObjectiveName(objective.key(), german);
        new ObjectiveDialog(
                        null,
                        null,
                        dialogs,
                        locale,
                        sampleMilestone,
                        7,
                        3,
                        exampleRows(),
                        exampleShare(),
                        (p, g) -> {},
                        Runnable::run,
                        drafted,
                        names,
                        key -> "minecraft:ender_pearl",
                        true)
                .show(reader, 0);
    }

    private static List<Objective> exampleObjectives() {
        return List.of(
                new Objective("iron", ObjectiveType.HAND_IN, "", 256, List.of("IRON_INGOT"), "", List.of(), "", 0, 8),
                new Objective(
                        "end",
                        ObjectiveType.ADVANCEMENT,
                        "",
                        10,
                        List.of(),
                        "",
                        List.of(),
                        "minecraft:story/enter_the_end",
                        0,
                        4),
                new Objective(
                        "zombies",
                        ObjectiveType.STATISTIC,
                        "",
                        50,
                        List.of(),
                        "KILL_ENTITY",
                        List.of("ZOMBIE"),
                        "",
                        0,
                        4),
                new Objective("wood", ObjectiveType.HAND_IN, "", 64, List.of("OAK_LOG"), "", List.of(), "", 0, 4),
                new Objective(
                        "stone",
                        ObjectiveType.STATISTIC,
                        "",
                        9000,
                        List.of(),
                        "MINE_BLOCK",
                        List.of("STONE"),
                        "",
                        0,
                        0),
                new Objective("diamonds", ObjectiveType.HAND_IN, "", 32, List.of("DIAMOND"), "", List.of(), "", 0, 0));
    }

    private static List<ObjectiveRow> exampleRows() {
        return List.of(
                new ObjectiveRow(new UUID(0, 1), "iron", 120, 256, false),
                new ObjectiveRow(new UUID(0, 2), "end", 3, 10, false),
                new ObjectiveRow(new UUID(0, 3), "zombies", 31, 50, false),
                new ObjectiveRow(new UUID(0, 4), "wood", 64, 64, true),
                new ObjectiveRow(new UUID(0, 5), "stone", 5210, 9000, false),
                new ObjectiveRow(new UUID(0, 6), "diamonds", 6, 32, false));
    }

    private static OwnShare.Summary exampleShare() {
        return new OwnShare.Summary(
                List.of(
                        new OwnShare.Line("iron", 41, 16.0, 1, 8),
                        new OwnShare.Line("end", 1, 10.0, 1, 4),
                        new OwnShare.Line("zombies", 4, 8.0, 0, 4),
                        new OwnShare.Line("wood", 9, 14.0, 0, 4),
                        new OwnShare.Line("stone", 388, 4.3, 0, 0),
                        new OwnShare.Line("diamonds", 2, 6.25, 0, 0)),
                10.0,
                2,
                20,
                true);
    }

    private static String exampleObjectiveName(final String key, final boolean german) {
        return switch (key) {
            case "iron" -> german ? "Eisen" : "Iron";
            case "end" -> german ? "Das Ende betreten" : "Enter the End";
            case "zombies" -> german ? "Zombies töten" : "Kill zombies";
            case "wood" -> german ? "Holz" : "Wood";
            case "stone" -> german ? "Stein abbauen" : "Mine stone";
            default -> german ? "Diamanten" : "Diamonds";
        };
    }

    public void show(final Player player, final int rowOffset) {
        player.showDialog(dialog(rowOffset));
    }

    Dialog dialog(final int rowOffset) {
        final Dialogs.Send send = dialogs.send();
        final List<Card> cards = new ArrayList<>();
        for (final ObjectiveRow row : rows) {
            milestone.objective(row.key()).ifPresent(objective -> cards.add(new Card(objective, row)));
        }
        final int totalRows = CardGrid.totalRows(cards.size());
        final ScrollState scroll = ScrollState.of(totalRows, VISIBLE_ROWS, rowOffset);
        final CardGrid grid = CardGrid.of(0, GRID_TOP, WIDTH, CARD_HEIGHT, CARD_ROW_GAP, scroll.scrollable());
        final DialogCanvas canvas = DialogCanvas.of(DialogPanel.WIDE_161);

        // Row 1: milestone rail and projected extra spins plate.
        canvas.rail(0, 2, RAIL_WIDTH, totalMilestones, currentMilestone);
        placeSpinsPlate(canvas);

        // Row 2: two-column objective cards and optional scroll column on the right.
        for (final CardGrid.Slot slot : grid.visible(cards.size(), scroll.offset(), VISIBLE_ROWS)) {
            placeCard(canvas, slot, cards.get(slot.index()), send);
        }
        if (scroll.scrollable()) {
            final @Nullable ClickEvent up =
                    scroll.canScrollUp() ? send.click(player -> show(player, scroll.stepUp())) : null;
            final @Nullable ClickEvent down =
                    scroll.canScrollDown() ? send.click(player -> show(player, scroll.stepDown())) : null;
            final @Nullable ClickEvent pageUp =
                    scroll.canScrollUp() ? send.click(player -> show(player, scroll.pageUp())) : null;
            final @Nullable ClickEvent pageDown =
                    scroll.canScrollDown() ? send.click(player -> show(player, scroll.pageDown())) : null;
            canvas.scrollColumn(
                    grid.scrollColumnX(),
                    GRID_TOP,
                    scroll,
                    up,
                    down,
                    pageUp,
                    pageDown,
                    document.label("scroll-up"),
                    document.label("scroll-down"));
        }

        final DialogTitle title = DialogTitle.of(DialogPanel.WIDE_161, canvas.panelTop())
                .header(Symbol.FLAG, Dialogs.plain(document.title()), List.of());
        final List<DialogBody> body = List.of(Dialogs.line(canvas, FocusRing.Ground.BEVEL_FOOT, title));
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title.build()).body(body).build())
                .type(Dialogs.bodyOnly()));
    }

    private void placeSpinsPlate(final DialogCanvas canvas) {
        final String label = share.available() ? Integer.toString(share.spins()) : "-";
        final Component tooltip = share.available()
                ? Tooltip.hover(
                        document.label("share-expected", new ShareLine(share.spins(), share.budget())), null, null)
                : Tooltip.hover(document.label("share-unlinked"), null, null);
        canvas.plate(
                WIDTH - SPINS_WIDTH,
                0,
                SPINS_WIDTH,
                Symbol.WHEEL,
                label,
                Look.NORMAL,
                new BodyLine.Press(null, tooltip));
    }

    private void placeCard(
            final DialogCanvas canvas, final CardGrid.Slot slot, final Card card, final Dialogs.Send send) {
        final Objective objective = card.objective();
        final ObjectiveRow row = card.row();
        final String name = objectiveName.apply(objective);
        final boolean done = row.completed();
        final long capped = Math.min(Math.max(0L, row.amount()), row.target());
        final Optional<OwnShare.Line> own = share.line(objective.key());
        final long ownMine = own.map(OwnShare.Line::mine).orElse(0L);
        final TextColor ink = done ? Colours.FAINT : Colours.INK;
        final TextColor soft = done ? Colours.FAINT : Colours.SOFT;

        final int cx = slot.x();
        final int cy = slot.y();
        final int cw = slot.width();
        final Component hover = cardTooltip(objective, name, capped, row.target(), done, ownMine);

        // Card background (hoverable for tooltip; completed cards are disabled with no click callback).
        canvas.cardPlate(cx, cy, cw, done ? Look.DISABLED : Look.NORMAL, new BodyLine.Press(null, hover));

        // Dark 22 x 22 item field and derived objective icon.
        canvas.darkField(cx + 5, cy + 5, ItemPicture.SMALL_FIELD);
        canvas.itemPicture(cx + 12, cy + 12, icon(objective, done, advancementIcon), BodyLine.Press.NONE);

        // Objective name cut to its room.
        canvas.text(cx + 32, cy + 6, Letters.cut(name, cw - 37), ink);

        // Progress bar and shortened count (label measured first so the bar gives way).
        final String count = shortCount(capped, locale) + "/" + shortCount(row.target(), locale);
        final int countWidth = Letters.visibleWidth(count);
        final boolean isAdv = objective.type() == ObjectiveType.ADVANCEMENT;
        final int countRoom = countWidth + (isAdv ? Symbol.BEFORE_TEXT : 0);
        final int barWidth = Math.max(0, cw - 32 - 5 - countRoom - 6);
        if (barWidth > 0) {
            canvas.varBar(cx + 32, cy + 19, barWidth, row.ratio(), 0.0, done);
        }
        final int countX = cx + cw - 6 - countWidth;
        if (isAdv) {
            canvas.symbol(countX - Symbol.BEFORE_TEXT, cy + 19, Symbol.PEOPLE, soft);
        }
        canvas.text(countX, cy + 19, count, soft);

        // Bottom-left: player's own contribution (distinguishing unlinked unavailable share from earned zero).
        placeCardFooter(canvas, cx, cy, cw, objective, row, done, isAdv, ownMine, soft, send);
    }

    private void placeCardFooter(
            final DialogCanvas canvas,
            final int cx,
            final int cy,
            final int cw,
            final Objective objective,
            final ObjectiveRow row,
            final boolean done,
            final boolean isAdv,
            final long ownMine,
            final TextColor soft,
            final Dialogs.Send send) {
        if (!share.available()) {
            canvas.text(cx + 5, cy + 38, "-", soft);
        } else if (isAdv) {
            if (ownMine > 0) {
                canvas.symbol(cx + 5, cy + 38, Symbol.TICK, soft);
            }
        } else {
            canvas.text(cx + 5, cy + 38, shortCount(ownMine, locale), soft);
        }

        if (done) {
            canvas.symbol(cx + cw - 12, cy + 38, Symbol.TICK, Colours.FAINT);
        } else if (objective.type() == ObjectiveType.HAND_IN) {
            final BodyLine.Press handInPress = readOnly
                    ? BodyLine.Press.NONE
                    : new BodyLine.Press(send.click(player -> openHandIn(player, objective, row)), null);
            canvas.plate(
                    cx + cw - 5 - HAND_IN_PLATE_WIDTH,
                    cy + 32,
                    HAND_IN_PLATE_WIDTH,
                    Symbol.HANDIN,
                    document.label("hand-in-button"),
                    Look.PRIMARY,
                    handInPress);
        }
    }

    private Component cardTooltip(
            final Objective objective,
            final String name,
            final long capped,
            final long target,
            final boolean done,
            final long ownMine) {
        final String progressLine = done
                ? document.label("finished")
                : document.label(
                        objective.type() == ObjectiveType.HAND_IN ? "progress-hand-in" : "progress-counted",
                        new Count(capped, target));
        final @Nullable String ownLine;
        if (!share.available()) {
            ownLine = document.label("share-unlinked");
        } else if (objective.type() == ObjectiveType.ADVANCEMENT) {
            ownLine = ownMine > 0 ? document.label("own-done") : null;
        } else {
            ownLine = document.label("own-amount", new OwnAmount(ownMine));
        }
        final String detail = ownLine == null ? progressLine : progressLine + "\n" + ownLine;
        return objectiveHover(objective, name, detail, null);
    }

    private Component objectiveHover(
            final Objective objective, final String name, final String detail, final @Nullable String hint) {
        final Objective.ItemReward reward = objective.itemReward();
        if (reward == null || renderer == null) {
            return Tooltip.hover(name, detail, hint);
        }
        final var displayed = TakenItems.one(reward.item(), reward.data());
        if (displayed == null) {
            return Tooltip.hover(name, detail, hint);
        }
        return renderer.format(
                locale,
                REWARD_HOVER.with(new RewardHover(
                        name,
                        detail,
                        reward.amount(),
                        GameLines.of(displayed.effectiveName()),
                        hint != null,
                        hint == null ? "" : hint)));
    }

    private void openHandIn(final Player player, final Objective objective, final ObjectiveRow row) {
        if (renderer == null || documents == null) {
            return;
        }
        new HandInGui(
                        renderer,
                        documents,
                        locale,
                        milestone.key(),
                        objective,
                        row.amount(),
                        row.target(),
                        row.completed(),
                        confirm,
                        nextTick)
                .open(player);
    }

    private record Card(Objective objective, ObjectiveRow row) {}

    /**
     * Derives an objective's icon from its accepted material, statistic subject or advancement.
     * Falls back to the objective type's symbol when no atlas sprite exists.
     */
    static ItemPicture icon(
            final Objective objective,
            final boolean done,
            final Function<NamespacedKey, @Nullable String> advancementIcon) {
        final TextColor fallbackColour = done ? Colours.FAINT : Colours.ACCENT;
        final Symbol fallback = symbol(objective);
        final @Nullable String itemKey = switch (objective.type()) {
            case HAND_IN ->
                objective.items().isEmpty()
                        ? null
                        : GameKeys.key(objective.items().getFirst());
            case STATISTIC ->
                objective.subjects().isEmpty()
                        ? null
                        : GameKeys.key(objective.subjects().getFirst());
            case ADVANCEMENT -> objective.advancementKey().map(advancementIcon).orElse(null);
        };
        return ItemPicture.of(itemKey, fallback, fallbackColour);
    }

    /** Looks up the Bukkit advancement's display icon key when a server is running, or null otherwise. */
    static @Nullable String advancementIconKey(final NamespacedKey key) {
        try {
            final @Nullable Advancement advancement = Bukkit.getAdvancement(key);
            if (advancement == null) {
                return null;
            }
            final @Nullable AdvancementDisplay display = advancement.getDisplay();
            if (display == null) {
                return null;
            }
            return display.icon().getType().getKey().asString();
        } catch (final RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** A statistic that counts kills shows the swords, any other the pickaxe; an advancement shows its flag. */
    static Symbol symbol(final Objective objective) {
        return switch (objective.type()) {
            case HAND_IN -> Symbol.HANDIN;
            case ADVANCEMENT -> Symbol.FLAG;
            case STATISTIC ->
                objective.statistic().toLowerCase(Locale.ROOT).contains("kill") ? Symbol.SWORDS : Symbol.PICKAXE;
        };
    }

    /**
     * Shortens counts from 1000 up in thousands, cut and never rounded up (8999 becomes 8.9k / 8,9k, never 9k).
     * Uses a comma in German and a dot in English.
     */
    static String shortCount(final long value, final Locale locale) {
        final long n = Math.max(0L, value);
        if (n < 1000L) {
            return Long.toString(n);
        }
        final long tenths = n / 100L;
        final long whole = tenths / 10L;
        final long frac = tenths % 10L;
        if (frac == 0L) {
            return whole + "k";
        }
        final char separator = Locale.GERMAN.getLanguage().equals(locale.getLanguage()) ? ',' : '.';
        return whole + String.valueOf(separator) + frac + "k";
    }
}
