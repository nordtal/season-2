package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.ui.Pill;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.papercommon.hud.HudLine;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.entity.Player;

/**
 * Says and draws each ride: departure and arrival in the action bar, the ride in the HUD.
 * The HUD line holds station, bar and metres. While the cart stands on a network, two lines say how to choose a
 * station and how to get out, high above the hint the game draws over the hotbar.
 */
public final class RideFeedback implements HudLine, RideWatch {

    record Destination(@Example("Harbour") String name) {}

    record Left(@Example("240") long distance) {}

    static final DocumentSpec<Destination> DEPARTING = DocumentSpec.game(
                    "smp", "ride-departing", "Rails", DocumentSpec.Shape.ACTION_BAR, Destination.class)
            .titled("Ride departing");

    static final DocumentSpec<Destination> ARRIVED = DocumentSpec.game(
                    "smp", "ride-arrived", "Rails", DocumentSpec.Shape.ACTION_BAR, Destination.class)
            .titled("Ride arrived");

    static final DocumentSpec<Left> DISTANCE = DocumentSpec.game(
                    "smp", "hud-ride-distance", "HUD", DocumentSpec.Shape.BOSS_BAR, Left.class)
            .titled("Distance of a ride in the HUD");

    static final DocumentSpec<DocumentSpec.None> CHOOSE = DocumentSpec.game(
                    "smp", "hud-ride-choose", "HUD", DocumentSpec.Shape.BOSS_BAR)
            .titled("Choosing a station from a standing cart in the HUD");

    static final DocumentSpec<DocumentSpec.None> DISMOUNT = DocumentSpec.game(
                    "smp", "hud-ride-dismount", "HUD", DocumentSpec.Shape.BOSS_BAR)
            .titled("Getting out of a standing cart in the HUD");

    private final MessageRenderer renderer;
    private final Identities identities;
    private final SmpSounds sounds;
    private final Clock clock;
    private final Function<Player, Optional<Boolean>> waiting;
    private final RideDisplay display = new RideDisplay();

    /**
     * Creates the feedback of every ride.
     *
     * @param waiting for a rider whose cart stands on a network, whether it has a station to choose; else empty
     */
    public RideFeedback(
            final MessageRenderer renderer,
            final Identities identities,
            final SmpSounds sounds,
            final Clock clock,
            final Function<Player, Optional<Boolean>> waiting) {
        this.renderer = renderer;
        this.identities = identities;
        this.sounds = sounds;
        this.clock = clock;
        this.waiting = waiting;
    }

    @Override
    public void planned(final Player rider, final UUID station, final String name, final double left) {
        display.planned(rider.getUniqueId(), station, name, left).ifPresent(departing -> {
            tell(rider, DEPARTING, departing);
            sounds.play(rider, Feedback.TRAVEL);
        });
    }

    @Override
    public void moved(final Player rider, final double left) {
        display.moved(rider.getUniqueId(), left);
    }

    @Override
    public void arrived(final Player rider, final UUID station, final String name) {
        display.arrived(rider.getUniqueId(), station, name, clock.millis());
        tell(rider, ARRIVED, name);
        sounds.play(rider, Feedback.SMALL_SUCCESS);
    }

    @Override
    public void ended(final Player rider) {
        display.ended(rider.getUniqueId());
    }

    /** The first line: the ride, or how to choose a station while the cart stands. */
    @Override
    public List<Pill.Content> render(final Player player, final Locale locale) {
        final Optional<RideDisplay.View> ride = display.view(player.getUniqueId(), clock.millis());
        if (ride.isPresent()) {
            return ride(ride.get(), locale);
        }
        return waiting.apply(player)
                .map(choices -> choices
                        ? List.of(Pill.Content.of(Symbol.RAIL, renderer.plainLine(locale, Sent.of(CHOOSE))))
                        : List.of(dismount(locale)))
                .orElse(List.of());
    }

    /** The station, a bar of the way done and the metres left. */
    private List<Pill.Content> ride(final RideDisplay.View view, final Locale locale) {
        return List.of(
                Pill.Content.of(Symbol.RAIL, view.station()),
                Pill.Content.bar(view.done(), renderer.plainLine(locale, DISTANCE.with(new Left(view.metres())))));
    }

    /** The second line: how to get out, while the cart stands and the first line shows how to choose. */
    public List<Pill.Content> below(final Player player, final Locale locale) {
        if (display.view(player.getUniqueId(), clock.millis()).isPresent()) {
            return List.of();
        }
        return waiting.apply(player)
                .filter(choices -> choices)
                .map(choices -> List.of(dismount(locale)))
                .orElse(List.of());
    }

    private Pill.Content dismount(final Locale locale) {
        return Pill.Content.of(renderer.plainLine(locale, Sent.of(DISMOUNT)));
    }

    private void tell(final Player rider, final DocumentSpec<Destination> message, final String name) {
        rider.sendActionBar(Lines.joined(
                renderer.format(identities.languageOf(rider.getUniqueId()), message.with(new Destination(name)))));
    }
}
