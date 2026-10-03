package eu.nordtal.s2.discordbot.hungergames;

import eu.nordtal.s2.common.id.DiscordId;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * Team registration over {@code hg_game}, {@code hg_team} and {@code hg_member}, from the Discord side.
 *
 * Every check is also a schema constraint; the Java checks only choose a specific result.
 */
public final class Teams {

    private static final int NAME_MIN_LENGTH = 3;
    private static final int NAME_MAX_LENGTH = 15;

    private final Jdbi jdbi;
    private final HungerGamesDao dao;

    public Teams(final Jdbi jdbi) {
        this.jdbi = jdbi;
        this.dao = jdbi.onDemand(HungerGamesDao.class);
    }

    /** Returns the id of the one open game, creating it if none exists yet. */
    public UUID openGame() {
        final Optional<UUID> existing = dao.openGameId();
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return dao.createGame();
        } catch (final UnableToExecuteStatementException exception) {
            // Somebody else's first registration created it a moment ago.
            if (isUniqueViolation(exception)) {
                return dao.openGameId().orElseThrow(() -> exception);
            }
            throw exception;
        }
    }

    /**
     * Registers a new team with {@code discordId} as its owner.
     *
     * @param discordId the registering Discord account
     * @param name the team name, 3 to 15 characters, unique within the open game
     */
    public RegistrationResult register(final DiscordId discordId, final String name) {
        final String trimmed = name == null ? "" : name.strip();
        if (trimmed.length() < NAME_MIN_LENGTH || trimmed.length() > NAME_MAX_LENGTH) {
            return RegistrationResult.invalidName();
        }

        final UUID gameId = openGame();
        if (dao.activeMembershipId(gameId, discordId).isPresent()) {
            return RegistrationResult.alreadyRegistered();
        }
        if (dao.teamNameTaken(gameId, trimmed)) {
            return RegistrationResult.nameTaken();
        }

        try {
            final UUID teamId = jdbi.inTransaction(handle -> {
                handle.createUpdate("INSERT INTO discord_user (discord_id) VALUES (:id) "
                                + "ON CONFLICT (discord_id) DO NOTHING")
                        .bind("id", discordId)
                        .execute();
                final HungerGamesDao txDao = handle.attach(HungerGamesDao.class);
                final UUID team = txDao.insertTeam(gameId, trimmed);
                txDao.insertOwner(team, gameId, discordId);
                return team;
            });
            return RegistrationResult.registered(teamId);
        } catch (final UnableToExecuteStatementException exception) {
            if (isUniqueViolation(exception)) {
                // Somebody else's registration landed between the check above and this transaction.
                return dao.activeMembershipId(gameId, discordId).isPresent()
                        ? RegistrationResult.alreadyRegistered()
                        : RegistrationResult.nameTaken();
            }
            throw exception;
        }
    }

    /**
     * Invites {@code partnerDiscordId} onto {@code ownerDiscordId}'s team.
     *
     * @param ownerDiscordId   must be the OWNER of a team in the open game
     * @param partnerDiscordId who is being invited
     */
    public InviteResult invite(final String ownerDiscordId, final String partnerDiscordId) {
        if (ownerDiscordId.equals(partnerDiscordId)) {
            return InviteResult.cannotInviteSelf();
        }

        final UUID gameId = openGame();
        final Optional<UUID> ownerMemberId = dao.activeMembershipId(gameId, DiscordId.of(ownerDiscordId));
        if (ownerMemberId.isEmpty()
                || !"OWNER".equals(dao.stateOfMember(ownerMemberId.get()).orElse(""))) {
            return InviteResult.notOwner();
        }
        final UUID teamId = dao.teamIdOfMember(ownerMemberId.get()).orElseThrow();

        if (dao.activeMembershipId(gameId, DiscordId.of(partnerDiscordId)).isPresent()) {
            return InviteResult.targetUnavailable();
        }
        if (dao.settledMemberCount(teamId) >= 2) {
            return InviteResult.teamFull();
        }
        if (dao.hasPendingInvite(teamId)) {
            return InviteResult.invitePending();
        }

        try {
            final UUID memberId = jdbi.inTransaction(handle -> {
                handle.createUpdate("INSERT INTO discord_user (discord_id) VALUES (:id) "
                                + "ON CONFLICT (discord_id) DO NOTHING")
                        .bind("id", partnerDiscordId)
                        .execute();
                return handle.attach(HungerGamesDao.class).insertInvite(teamId, gameId, DiscordId.of(partnerDiscordId));
            });
            return InviteResult.invited(memberId, teamId, dao.teamName(teamId).orElseThrow());
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
            return AnswerResult.notPending();
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
