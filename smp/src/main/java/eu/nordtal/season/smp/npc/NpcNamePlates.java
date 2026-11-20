package eu.nordtal.season.smp.npc;

import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.displaytags.nametag.NameTagManager;
import eu.nordtal.season.packrendering.npc.NpcPlate;
import eu.nordtal.season.papercommon.hud.PillHologram;
import eu.nordtal.season.papercommon.npc.NamePlates;
import eu.nordtal.season.papercommon.npc.NpcLooks;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Draws each figure's only label through the name tags: its plate, with its name in each viewer's language.
 * The plate takes the style, colour and icon chosen in Steward. A mannequin's own name is one text for every viewer,
 * so the figure carries none.
 */
public final class NpcNamePlates implements NamePlates {

    /** How close a viewer stands before the speech bubble over the plate shows that the NPC talks. */
    static final double NEAR = 6;

    private final NameTagManager nameTags;
    private final Function<UUID, Locale> languageOf;

    public NpcNamePlates(final NameTagManager nameTags, final Function<UUID, Locale> languageOf) {
        this.nameTags = nameTags;
        this.languageOf = languageOf;
    }

    @Override
    public void attach(final Mannequin figure, final Npc npc) {
        nameTags.createEntityNameTag(figure, plate(npc, languageOf, viewer -> near(figure, Bukkit.getPlayer(viewer))));
    }

    @Override
    public void detach(final Mannequin figure) {
        nameTags.removeEntityNameTag(figure);
    }

    /** Draws the plate of {@code npc} for each viewer, in their language, with the bubble for a viewer {@code near}. */
    static Function<UUID, PillHologram.Layers> plate(
            final Npc npc, final Function<UUID, Locale> languageOf, final Predicate<UUID> near) {
        final NpcPlate.Look look = NpcLooks.plate(npc);
        return viewer -> {
            final String name = npc.name().in(languageOf.apply(viewer));
            final boolean bubble = near.test(viewer);
            return new PillHologram.Layers(NpcPlate.panel(look, name, bubble), NpcPlate.ink(look, name, bubble));
        };
    }

    private static boolean near(final Mannequin figure, final @Nullable Player viewer) {
        return viewer != null
                && viewer.getWorld().equals(figure.getWorld())
                && Objects.requireNonNull(viewer.getLocation(), "an online player has a location")
                                .distanceSquared(figure.getLocation())
                        <= NEAR * NEAR;
    }
}
