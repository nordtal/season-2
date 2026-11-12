package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.feedback.FeedbackSounds;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.packrendering.ui.Pill;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** Holds the two HUD lines of a rider: the ride while it runs, how to choose and get out while the cart stands. */
class RideFeedbackTest {

    private static final MessageRenderer RENDERER = MessageRenderer.of(
                    Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
            .reading(new AppliedDocuments(RideFeedbackTest.class.getClassLoader(), Set.of("smp")));

    private static final UUID RIDER = UUID.randomUUID();

    private static final Player PLAYER = (Player) Proxy.newProxyInstance(
            RideFeedbackTest.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                if (method.getName().equals("getUniqueId")) {
                    return RIDER;
                }
                if (method.getName().equals("sendActionBar")) {
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void aStandingCartWithAStationToChooseShowsHowToChooseOverHowToGetOut() {
        final RideFeedback feedback = feedback(Optional.of(true));

        assertEquals(
                List.of(Pill.Content.of(Symbol.RAIL, "Rechtsklick auf die Lore: Bahnhof wählen")),
                feedback.render(PLAYER, Locale.GERMAN));
        assertEquals(List.of(Pill.Content.of("Schleichen zum Absteigen")), feedback.below(PLAYER, Locale.GERMAN));
    }

    @Test
    void aStandingCartWithNoStationToChooseShowsOnlyHowToGetOut() {
        final RideFeedback feedback = feedback(Optional.of(false));

        assertEquals(List.of(Pill.Content.of("Sneak to dismount")), feedback.render(PLAYER, Locale.ENGLISH));
        assertEquals(List.of(), feedback.below(PLAYER, Locale.ENGLISH));
    }

    @Test
    void aPlayerOnFootSeesNoHudLine() {
        final RideFeedback feedback = feedback(Optional.empty());

        assertEquals(List.of(), feedback.render(PLAYER, Locale.ENGLISH));
        assertEquals(List.of(), feedback.below(PLAYER, Locale.ENGLISH));
    }

    @Test
    void aRunningRideIsTheOnlyLine() {
        final RideFeedback feedback = feedback(Optional.of(true));
        feedback.planned(PLAYER, UUID.randomUUID(), "Harbour", 240);

        assertEquals(
                List.of(Pill.Content.of(Symbol.RAIL, "Harbour"), Pill.Content.bar(0, "240 m")),
                feedback.render(PLAYER, Locale.ENGLISH));
        assertEquals(List.of(), feedback.below(PLAYER, Locale.ENGLISH));
    }

    private static RideFeedback feedback(final Optional<Boolean> waiting) {
        return new RideFeedback(
                RENDERER,
                new Identities(players -> List.of()),
                new SmpSounds(FeedbackSounds.silent(), _ -> {}),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                player -> waiting);
    }
}
