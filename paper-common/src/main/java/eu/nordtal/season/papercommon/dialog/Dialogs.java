package eu.nordtal.season.papercommon.dialog;

import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.packrendering.ui.BodyLine;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.set.RegistrySet;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

/**
 * The clicks of Nordtal's dialogs: each send of a dialog carries one token, and its first click spends it.
 * An answered click runs on the main thread, where the server redraws or closes the dialog. A drawn button's click
 * plays the select sound there; a native button plays its own on the client.
 */
public final class Dialogs {

    private static final ClickCallback.Options ONCE =
            ClickCallback.Options.builder().uses(1).build();

    /** A click ignored while busy must not use the callback up, or the click after it would find none. */
    static final ClickCallback.Options KEPT_WHILE_BUSY =
            ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build();

    /** A body line's box is this much wider than its art. */
    private static final int RING = 10;

    private final Executor main;
    private final BiConsumer<Player, Feedback> chime;

    /** @param main runs work on the server's main thread */
    public Dialogs(final Executor main, final BiConsumer<Player, Feedback> chime) {
        this.main = Objects.requireNonNull(main, "main");
        this.chime = Objects.requireNonNull(chime, "chime");
    }

    /** Starts the clicks of one send of a dialog. */
    public Send send() {
        return new Send();
    }

    /**
     * The base of a dialog with drawn buttons, closed by Esc.
     * Esc tells the server nothing, a click keeps the dialog open for the server's redraw, and the game runs on.
     */
    public static DialogBase.Builder base(final Component title) {
        return DialogBase.builder(title)
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .pause(false);
    }

    /** A dialog of its body alone: no button grid and no footer button. */
    public static DialogType bodyOnly() {
        return DialogType.dialogList(RegistrySet.keySet(RegistryKey.DIALOG)).build();
    }

    /** A body line whose art is {@code art} wide: its box is 10 wider, so the focus ring stands clear of the art. */
    public static DialogBody line(final Component line, final int art) {
        return DialogBody.plainMessage(line, art + RING);
    }

    /** Returns {@code text} as the plain words a drawn header or row takes. */
    public static String plain(final Component text) {
        return Objects.requireNonNull(PlainTextComponentSerializer.plainText().serialize(text));
    }

    /** The width of the pager of {@code paging} at {@code page}, counted from 0. */
    public static int pagerWidth(final Paging paging, final int page) {
        return BodyLine.pagerWidth(paging.clamp(page) + 1, paging.pages());
    }

    /** One send: every click it carries shares its token. */
    public final class Send {

        private final AtomicBoolean spent = new AtomicBoolean();

        private Send() {}

        /** The click of a drawn button, which answers {@code answer} with the player who clicked. */
        public ClickEvent click(final Consumer<Player> answer) {
            return ClickEvent.callback(audience -> answer(audience, true, player -> answer.accept(player)), ONCE);
        }

        /**
         * The click of a drawn button that does nothing while {@code busy} holds and keeps the send's token for later.
         */
        public ClickEvent clickUnless(final BooleanSupplier busy, final Consumer<Player> answer) {
            return ClickEvent.callback(
                    audience -> {
                        if (audience instanceof final Player player) {
                            answerUnless(busy, () -> {
                                chime.accept(player, Feedback.SELECT);
                                answer.accept(player);
                            });
                        }
                    },
                    KEPT_WHILE_BUSY);
        }

        /**
         * Draws the pager of {@code paging} at {@code page} on {@code line}.
         *
         * Each arrow answers {@code turn} with its page; {@code words} are what the narrator says.
         */
        public BodyLine pager(
                final BodyLine line,
                final Paging paging,
                final int page,
                final String words,
                final BiConsumer<Player, Integer> turn) {
            final int shown = paging.clamp(page);
            return line.pager(
                    shown + 1,
                    paging.pages(),
                    new BodyLine.Press(click(player -> turn.accept(player, paging.next(shown))), null),
                    new BodyLine.Press(click(player -> turn.accept(player, paging.previous(shown))), null),
                    words);
        }

        /** The action of a native button, which answers {@code answer} with the player and what the inputs hold. */
        public DialogAction action(final BiConsumer<Player, DialogResponseView> answer) {
            return DialogAction.customClick(
                    (response, audience) -> answer(audience, false, player -> answer.accept(player, response)), ONCE);
        }

        private void answer(final Audience audience, final boolean sound, final Consumer<Player> work) {
            if (audience instanceof final Player player) {
                answer(() -> {
                    if (sound) {
                        chime.accept(player, Feedback.SELECT);
                    }
                    work.accept(player);
                });
            }
        }

        /** Ignores the click while {@code busy} holds, keeping the token; otherwise answers it as {@link #answer}. */
        boolean answerUnless(final BooleanSupplier busy, final Runnable work) {
            return !busy.getAsBoolean() && answer(work);
        }

        /** Runs {@code work} on the main thread for the first click of this send, and returns whether it did. */
        boolean answer(final Runnable work) {
            if (!spent.compareAndSet(false, true)) {
                return false;
            }
            main.execute(work);
            return true;
        }
    }
}
