package eu.nordtal.s2.discordbot.registration;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.registration.Game;
import eu.nordtal.s2.database.registration.Membership;
import eu.nordtal.s2.database.registration.RegistrationState;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * Team registration for one game over {@code registration}, {@code team} and {@code team_member}.
 *
 * Every check is also a schema constraint and only picks the answer; nothing changes while the game holds the round.
 */
public final class Teams {

    private static final int NAME_MIN_LENGTH = 3;
    private static final int NAME_MAX_LENGTH = 15;

    private final Jdbi jdbi;
    private final Game game;
    private final RegistrationDao dao;

    public Teams(final Jdbi jdbi, final Game game) {
        this.jdbi = jdbi;
        this.game = game;
        this.dao = jdbi.onDemand(RegistrationDao.class);
    }

    /** Returns the game these teams register for. */
    public Game game() {
        return game;
    }

    /** Returns the round that has not ended, opening a new one after the last game was decided. */
    RegistrationDao.Current current() {
        final Optional<RegistrationDao.Current> existing = dao.current(game.key());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return dao.open(game.key());
        } catch (final UnableToExecuteStatementException exception) {
            // Somebody else's first registration opened it a moment ago.
            if (isUniqueViolation(exception)) {
                return dao.current(game.key()).orElseThrow(() -> exception);
            }
            throw exception;
        }
    }

    /**
     * Registers a new team with {@code discordId} as its owner.
     *
     * @param discordId the registering Discord account
     * @param name the team name, 3 to 15 characters, unique within the round
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
            // A game started between the check above and this transaction.
            return teamId.map(RegistrationResult::registered).orElseGet(RegistrationResult::closed);
        } catch (final UnableToExecuteStatementException exception) {
            if (isUniqueViolation(exception)) {
                // Somebody else's registration landed between the check above and this transaction.
                return dao.activeMembershipId(registrationId, discordId).isPresent()
                        ? RegistrationResult.alreadyRegistered()
                        : RegistrationResult.nameTaken();
            }
            throw exception;
        }
    }

    /**
     * Invites {@code partnerDiscordId} onto {@code ownerDiscordId}'s team.
     *
     * @param ownerDiscordId   must be the OWNER of a team in the current round
     * @param partnerDiscordId who is being invited
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
     * Accepts an invite on behalf of the invited account.
     *
     * @param memberId the INVITED row's id, carried by the accept button
     * @param respondingDiscordId only this account's own invite can be answered with it
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

    /** Declines an invite, with the same rules as {@link #accept(UUID, String)}. */
    public AnswerResult decline(final UUID memberId, final String respondingDiscordId) {
        if (dao.decline(memberId, DiscordId.of(respondingDiscordId)) != 1) {
            return AnswerResult.notPending();
        }
        final UUID teamId = dao.teamIdOfMember(memberId).orElseThrow();
        return AnswerResult.answered(teamId, dao.teamName(teamId).orElseThrow());
    }

    /** Returns the OWNER of a team, to report an answer to an invite back to. */
    public Optional<String> ownerOf(final UUID teamId) {
        return dao.ownerDiscordId(teamId);
    }

    private static boolean isUniqueViolation(final UnableToExecuteStatementException exception) {
        return exception.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState());
    }
}
