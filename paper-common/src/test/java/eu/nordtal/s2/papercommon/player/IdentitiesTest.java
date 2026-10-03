package eu.nordtal.s2.papercommon.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.database.access.PlayerIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class IdentitiesTest {

    private static final PlayerId ALEX = PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final PlayerId SAM = PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000b"));

    /** What the database holds now; a test writes here and the cache reads it on load and reread. */
    private final Map<PlayerId, PlayerIdentity> rows = new ConcurrentHashMap<>();

    private final Identities identities = new Identities(
            players -> players.stream().filter(rows::containsKey).map(rows::get).toList());
    private final List<PlayerIdentity> told = new ArrayList<>();

    {
        identities.whenChanged(told::add);
    }

    private static PlayerIdentity row(final PlayerId player, final int aura) {
        return new PlayerIdentity(player, DiscordId.of("1"), "Alex", Locale.GERMAN, null, false, false, aura, 0L);
    }

    @Test
    void aRereadTellsTheWatchersOfWhatChangedAndOfNothingElse() {
        rows.put(ALEX, row(ALEX, 10));
        rows.put(SAM, row(SAM, 5));
        identities.load(ALEX);
        identities.load(SAM);

        rows.put(ALEX, row(ALEX, 11));
        identities.reread();
        identities.reread();

        assertEquals(List.of(row(ALEX, 11)), told);
        assertEquals(11, identities.of(ALEX).aura());
    }

    @Test
    void aPlayerWhoLeftIsNotPutBackByAReread() {
        rows.put(ALEX, row(ALEX, 10));
        identities.load(ALEX);
        identities.forget(ALEX);

        rows.put(ALEX, row(ALEX, 11));
        identities.reread();

        assertEquals(0, identities.size());
        assertEquals(List.of(), told);
    }

    @Test
    void anAccountNobodyLinkedIsHeldAsUnknownAndReadsTheNetworksLanguage() {
        assertEquals(PlayerIdentity.unknown(ALEX), identities.load(ALEX));
        assertEquals(Locale.ENGLISH, identities.languageOf(ALEX.value()));
    }
}
