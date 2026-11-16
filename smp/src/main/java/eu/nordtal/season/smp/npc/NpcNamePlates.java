package eu.nordtal.season.smp.npc;

import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.displaytags.nametag.NameTagManager;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.papercommon.hud.PillHologram;
import eu.nordtal.season.papercommon.npc.NamePlates;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.entity.Mannequin;

/**
 * Draws each figure's only label through the name tags, in the language of each viewer.
 * A mannequin's own name is one text for every viewer, so the figure carries none.
 */
public final class NpcNamePlates implements NamePlates {

    private final NameTagManager nameTags;
    private final Function<UUID, Locale> languageOf;
    private final MessageRenderer renderer;

    public NpcNamePlates(
            final NameTagManager nameTags, final Function<UUID, Locale> languageOf, final MessageRenderer renderer) {
        this.nameTags = nameTags;
        this.languageOf = languageOf;
        this.renderer = renderer;
    }

    @Override
    public void attach(final Mannequin figure, final Npc npc) {
        nameTags.createEntityNameTag(figure, plate(npc, languageOf, renderer));
    }

    @Override
    public void detach(final Mannequin figure) {
        nameTags.removeEntityNameTag(figure);
    }

    /** Draws the name of {@code npc} on a grey pill without a symbol, in the language of each viewer. */
    static Function<UUID, PillHologram.Layers> plate(
            final Npc npc, final Function<UUID, Locale> languageOf, final MessageRenderer renderer) {
        return viewer -> {
            final Locale locale = languageOf.apply(viewer);
            return PillHologram.layers(
                    null,
                    PillHologram.runs(
                            renderer.format(
                                    locale,
                                    NpcTexts.NAME_PLATE.with(
                                            new NpcTexts.Plate(npc.name().in(locale)))),
                            renderer.raw().environment().palette()));
        };
    }
}
