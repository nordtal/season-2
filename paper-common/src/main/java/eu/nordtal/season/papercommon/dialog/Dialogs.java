package eu.nordtal.season.papercommon.dialog;

import eu.nordtal.season.messages.feedback.Feedback;
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
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

/**
 * The clicks of Nordtal's dialogs: each send of a dialog carries one token, and its first click spends it.
 * An answered click plays the select sound and runs on the main thread, where the server redraws or closes the dialog.
 */
public final class Dialogs {

    private static final ClickCallback.Options ONCE =
            ClickCallback.Options.builder().uses(1).build();

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

    /** One send: every click it carries shares its token. */
    public final class Send {

        private final AtomicBoolean spent = new AtomicBoolean();

        private Send() {}

        /** The click of a drawn button, which answers {@code answer} with the player who clicked. */
        public ClickEvent click(final Consumer<Player> answer) {
            return ClickEvent.callback(audience -> answer(audience, () -> answer.accept((Player) audience)), ONCE);
        }

        /** The action of a native button, which answers {@code answer} with the player and what the inputs hold. */
        public DialogAction action(final BiConsumer<Player, DialogResponseView> answer) {
            return DialogAction.customClick(
                    (response, audience) -> answer(audience, () -> answer.accept((Player) audience, response)), ONCE);
        }

        private void answer(final Audience audience, final Runnable work) {
            if (audience instanceof final Player player) {
                answer(() -> {
                    chime.accept(player, Feedback.SELECT);
                    work.run();
                });
            }
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
