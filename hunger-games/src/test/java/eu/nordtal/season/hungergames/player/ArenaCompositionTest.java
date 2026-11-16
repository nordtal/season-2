package eu.nordtal.season.hungergames.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.access.PlayerIdentity;
import eu.nordtal.season.messages.value.DisplayName;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.papercommon.player.Identities;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ArenaCompositionTest {

    private static final PlayerId ALEX = PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));

    @Test
    void theFlagIsTheCountryNotTheLanguage() {
        final Identities identities = new Identities(players -> List.of(new PlayerIdentity(
                ALEX, DiscordId.of("1"), "Alex", Locale.GERMAN, "flag-austria", null, false, false, 0, 0L)));
        identities.load(ALEX);

        assertEquals(Glyphs.FLAG_AUSTRIA + " Alex", plain(identities));
    }

    @Test
    void aPlayerWithoutACountryShowsTheOtherFlag() {
        final Identities identities = new Identities(players -> List.of());
        identities.load(ALEX);

        assertEquals(Glyphs.FLAG_OTHER + " Alex", plain(identities));
    }

    private static String plain(final Identities identities) {
        return PlainTextComponentSerializer.plainText()
                .serialize(new ArenaComposition(identities).draw(new DisplayName(ALEX, "Alex"), Locale.ENGLISH));
    }
}
