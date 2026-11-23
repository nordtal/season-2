package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.packrendering.ui.DialogCanvas;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import org.junit.jupiter.api.Test;

/** Tests that the kit dialog's sprites stand where their field and frames centre them. */
class NegotiationDialogTest {

    @Test
    void theKitSpriteAndTheFacesStandOnTextRows() {
        final DialogCanvas canvas = DialogCanvas.of(DialogPanel.COMPACT_116);
        final int kit = NegotiationDialog.TILE_Y + NegotiationDialog.MIDDLE;
        final int face = NegotiationDialog.TILE_Y - NegotiationDialog.FACE_UP + NegotiationDialog.FACE_IN;
        assertEquals(kit, canvas.spriteY(kit), "kit sprite");
        assertEquals(face, canvas.spriteY(face), "face");
    }
}
