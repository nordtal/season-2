package eu.nordtal.season.smp.capture;

import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.util.Objects;
import org.bukkit.entity.Player;

/** Sends consistent action-bar messages and feedback sounds across all in-game capture modes. */
public final class CaptureFeedback {

    public static final DocumentSpec<DocumentSpec.None> POSITION_TAKEN = DocumentSpec.game(
                    "smp", "capture-position-taken", "In-game capture", DocumentSpec.Shape.ACTION_BAR)
            .titled("Position captured");

    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;

    public CaptureFeedback(final Identities identities, final MessageRenderer renderer, final SmpSounds sounds) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.sounds = Objects.requireNonNull(sounds, "sounds");
    }

    /** Shows {@code document} on the player's action bar without playing a sound. */
    public void show(final Player player, final DocumentSpec<DocumentSpec.None> document) {
        player.sendActionBar(
                Lines.joined(renderer.format(identities.languageOf(player.getUniqueId()), Sent.of(document))));
    }

    /** Shows {@code document} on the player's action bar and plays {@code feedback}. */
    public void tell(final Player player, final DocumentSpec<DocumentSpec.None> document, final Feedback feedback) {
        show(player, document);
        sounds.play(player, feedback);
    }
}
