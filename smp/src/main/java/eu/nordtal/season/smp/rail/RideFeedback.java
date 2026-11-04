package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import eu.nordtal.season.packrendering.hud.BossBarProgress;
import eu.nordtal.season.papercommon.hud.HudLine;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Says and draws what {@link RideDisplay} decides about each ride.
 * The departure and the arrival go to the action bar with a sound, and between them a HUD line holds the station,
 * a progress bar and the metres left.
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

    private final MessageRenderer renderer;
    private final Identities identities;
    private final SmpSounds sounds;
    private final Clock clock;
    private final RideDisplay display = new RideDisplay();

    public RideFeedback(
            final MessageRenderer renderer, final Identities identities, final SmpSounds sounds, final Clock clock) {
        this.renderer = renderer;
        this.identities = identities;
        this.sounds = sounds;
        this.clock = clock;
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

    @Override
    public List<Pill> render(final Player player, final Locale locale) {
        return display.view(player.getUniqueId(), clock.millis())
                .map(view -> List.of(
                        Pill.of(Glyphs.BOSSBAR_ICON_RIDE, view.station()),
                        Pill.of(
                                BossBarProgress.bar(view.done()),
                                renderer.plainLine(locale, DISTANCE.with(new Left(view.metres()))))))
                .orElse(List.of());
    }

    private void tell(final Player rider, final DocumentSpec<Destination> message, final String name) {
        rider.sendActionBar(Lines.joined(
                renderer.format(identities.languageOf(rider.getUniqueId()), message.with(new Destination(name)))));
    }
}
