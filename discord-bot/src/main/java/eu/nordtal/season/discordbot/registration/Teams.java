package eu.nordtal.season.discordbot.registration;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.database.registration.Membership;
import eu.nordtal.season.database.registration.RegistrationState;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * Registers teams of one game in {@code registration}, {@code team} and {@code team_member}.
 *
 * Every check is also a schema constraint and only chooses the result. Nothing changes while the game holds the round.
 */
public final class Teams {

    static final int NAME_MIN_LENGTH = 3;
    static final int NAME_MAX_LENGTH = 15;

    private final Jdbi jdbi;
    private final Game game;
    private final RegistrationDao dao;

    public Teams(final Jdbi jdbi, final Game game) {
        this.jdbi = jdbi;
        this.game = game;
        this.dao = jdbi.onDemand(RegistrationDao.class);
    }

    public Game game() {
        return game;
    }

    /** Returns the round that has not ended. It opens a new round when none exists. */
    RegistrationDao.Current current() {
        final Optional<RegistrationDao.Current> existing = dao.current(game.key());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return dao.open(game.key());
        } catch (final UnableToExecuteStatementException exception) {
            // Another registration opened the round meanwhile.
            if (isUniqueViolation(exception)) {
                return dao.current(game.key()).orElseThrow(() -> exception);
            }
            throw exception;
        }
    }

    /**
     * Registers a new team with {@code discordId} as its owner.
     *
     * @param name the team name, 3 to 15 characters and unique in the round
     */
    public RegistrationResult register(final DiscordId discordId, final String name) {
        final String trimmed = name == null ? "" : name.strip();
        if (trimmed.length() < NAME_MIN_LENGTH || trimmed.length() > NAME_MAX_LENGTH) {
            return RegistrationResult.invalidName();
        }

        final RegistrationDao.Current round = current();
        if (round.state() != RegistrationState.OPEN) {
            return RegistrationResult.closed();
        }
        final UUID registrationId = round.id();
        if (dao.activeMembershipId(registrationId, discordId).isPresent()) {
            return RegistrationResult.alreadyRegistered();
        }
        if (dao.teamNameTaken(registrationId, trimmed)) {
            return RegistrationResult.nameTaken();
        }

        try {
            final Optional<UUID> teamId = jdbi.inTransaction(handle -> {
                final RegistrationDao txDao = handle.attach(RegistrationDao.class);
                if (txDao.holdOpen(registrationId).isEmpty()) {
                    return Optional.<UUID>empty();
                }
                handle.createUpdate("INSERT INTO discord_user (discord_id) VALUES (:id) "
                                + "ON CONFLICT (discord_id) DO NOTHING")
                        .bind("id", discordId)
                        .execute();
                final UUID team = txDao.insertTeam(registrationId, trimmed);
                txDao.insertOwner(team, registrationId, discordId);
                return Optional.of(team);
            });
            // A game started after the check.
            return teamId.map(RegistrationResult::registered).orElseGet(RegistrationResult::closed);
        } catch (final UnableToExecuteStatementException exception) {
            if (isUniqueViolation(exception)) {
                // Another registration was written after the check.
                return dao.activeMembershipId(registrationId, discordId).isPresent()
                        ? RegistrationResult.alreadyRegistered()
                        : RegistrationResult.nameTaken();
            }
            throw exception;
        }
    }

    /**
     * Invites {@code partnerDiscordId} to the team of {@code ownerDiscordId}.
     *
     * @param ownerDiscordId the OWNER of a team in the current round
     */
    public InviteResult invite(final String ownerDiscordId, final String partnerDiscordId) {
        if (ownerDiscordId.equals(partnerDiscordId)) {
            return InviteResult.cannotInviteSelf();
        }

        final RegistrationDao.Current round = current();
        if (round.state() != RegistrationState.OPEN) {
            return InviteResult.closed();
        }
        final UUID registrationId = round.id();
        final Optional<UUID> ownerMemberId = dao.activeMembershipId(registrationId, DiscordId.of(ownerDiscordId));
        if (ownerMemberId.isEmpty()
                || dao.stateOfMember(ownerMemberId.get())
                        .filter(Membership.OWNER::equals)
                        .isEmpty()) {
            return InviteResult.notOwner();
        }
        final UUID teamId = dao.teamIdOfMember(ownerMemberId.get()).orElseThrow();

        if (dao.activeMembershipId(registrationId, DiscordId.of(partnerDiscordId))
                .isPresent()) {
            return InviteResult.targetUnavailable();
        }
        if (dao.settledMemberCount(teamId) >= 2) {
            return InviteResult.teamFull();
        }
        if (dao.hasPendingInvite(teamId)) {
            return InviteResult.invitePending();
        }

        try {
            final Optional<UUID> memberId = jdbi.inTransaction(handle -> {
                final RegistrationDao txDao = handle.attach(RegistrationDao.class);
                if (txDao.holdOpen(registrationId).isEmpty()) {
                    return Optional.<UUID>empty();
                }
                handle.createUpdate("INSERT INTO discord_user (discord_id) VALUES (:id) "
                                + "ON CONFLICT (discord_id) DO NOTHING")
                        .bind("id", partnerDiscordId)
                        .execute();
                return Optional.of(txDao.insertInvite(teamId, registrationId, DiscordId.of(partnerDiscordId)));
            });
            if (memberId.isEmpty()) {
                return InviteResult.closed();
            }
            return InviteResult.invited(
                    memberId.get(), teamId, dao.teamName(teamId).orElseThrow());
        } catch (final UnableToExecuteStatementException exception) {
            if (isUniqueViolation(exception)) {
                return InviteResult.targetUnavailable();
            }
            throw exception;
        }
    }

    /**
     * Accepts an invite.
     *
     * @param memberId the id of the INVITED row, which the accept button holds
     * @param respondingDiscordId the invited account, which is the only account that can answer
     */
    public AnswerResult accept(final UUID memberId, final String respondingDiscordId) {
        if (dao.accept(memberId, DiscordId.of(respondingDiscordId)) != 1) {
            final boolean closed = dao.registrationStateOf(memberId)
                    .filter(state -> state == RegistrationState.CLOSED)
                    .isPresent();
            return closed
                            && dao.stateOfMember(memberId)
                                    .filter(Membership.INVITED::equals)
                                    .isPresent()
                    ? AnswerResult.closed()
                    : AnswerResult.notPending();
        }
        final UUID teamId = dao.teamIdOfMember(memberId).orElseThrow();
        return AnswerResult.answered(teamId, dao.teamName(teamId).orElseThrow());
    }

    /** Declines an invite under the rules of {@link #accept(UUID, String)}. */
    public AnswerResult decline(final UUID memberId, final String respondingDiscordId) {
        if (dao.decline(memberId, DiscordId.of(respondingDiscordId)) != 1) {
            return AnswerResult.notPending();
        }
        final UUID teamId = dao.teamIdOfMember(memberId).orElseThrow();
        return AnswerResult.answered(teamId, dao.teamName(teamId).orElseThrow());
    }

    public Optional<String> ownerOf(final UUID teamId) {
        return dao.ownerDiscordId(teamId);
    }

    private static boolean isUniqueViolation(final UnableToExecuteStatementException exception) {
        return exception.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState());
    }
}
