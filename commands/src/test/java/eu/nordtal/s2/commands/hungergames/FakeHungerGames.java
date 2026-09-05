package eu.nordtal.s2.commands.hungergames;

import eu.nordtal.s2.commands.NordtalUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The hunger games server, as far as a {@code /hg} command can tell. */
final class FakeHungerGames implements HungerGamesEffects {

    static final UUID GAME = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    final List<String> did = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    Registration registration;
    List<TeamReady> teams = List.of();
    int softMinimum = 8;
    boolean soundsReload = true;
    boolean messagesReload = true;
    RuntimeException failure;

    static Registration registered(final int participants) {
        return new Registration(GAME, "REGISTRATION", participants);
    }

    @Override
    public void async(final Runnable work) {
        work.run();
    }

    @Override
    public void warn(final String what, final Throwable cause) {
        warnings.add(what);
    }

    @Override
    public Optional<Registration> registration() {
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(registration);
    }

    RuntimeException startFailure;

    @Override
    public void start(final UUID gameId) {
        did.add("start " + gameId);
        if (startFailure != null) {
            throw startFailure;
        }
    }

    @Override
    public boolean reloadSounds() {
        did.add("reload sounds");
        return soundsReload;
    }

    @Override
    public boolean reloadMessages() {
        did.add("reload messages");
        return messagesReload;
    }

    @Override
    public List<TeamReady> readyStatus(final UUID gameId) {
        return teams;
    }

    @Override
    public int softMinimumParticipants() {
        return softMinimum;
    }

    @Override
    public void recordStart(final NordtalUser who, final Registration game,
                            final boolean confirmedBelowMinimum) {
        did.add("logged " + who.name() + (confirmedBelowMinimum ? " (confirmed)" : ""));
    }
}
