package eu.nordtal.s2.discordbot.hungergames;

import eu.nordtal.s2.common.message.Locales;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Team registration over {@code hg_game}/{@code hg_team}/{@code hg_member}, from the Discord side.
 *
 * <p>Registration needs no admin to start anything: {@link #openGame()} creates the one non-DECIDED
 * {@code hg_game} row lazily, on the first attempt. {@code hg_game_one_open_key} makes that safe
 * under a race - the loser re-reads the row the winner created.</p>
 *
 * <p>Every check here is also a schema constraint. The Java checks exist only to answer with a
 * specific result rather than a generic failure; a race that slips past them still cannot write a
 * bad row.</p>
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

    /** @return the id of the one open (non-DECIDED) game, creating it if none exists yet */
    public UUID openGame() {
        final Optional<UUID> existing = dao.openGameId();
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return dao.createGame();
        } catch (final UnableToExecuteStatementException exception) {
            // hg_game_one_open_key: somebody else's first registration created it a moment ago.
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
     * @param name      the team name, 3-15 characters, unique within the open game
     */
    public RegistrationResult register(final String discordId, final String name) {
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
                // hg_team_game_id_name_lower_key or hg_member_one_active_membership_key: somebody
                // else's registration landed between the check above and this transaction.
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
        final Optional<UUID> ownerMemberId = dao.activeMembershipId(gameId, ownerDiscordId);
        if (ownerMemberId.isEmpty() || !"OWNER".equals(dao.stateOfMember(ownerMemberId.get()).orElse(""))) {
            return InviteResult.notOwner();
        }
        final UUID teamId = dao.teamIdOfMember(ownerMemberId.get()).orElseThrow();

        if (dao.activeMembershipId(gameId, partnerDiscordId).isPresent()) {
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
                return handle.attach(HungerGamesDao.class).insertInvite(teamId, gameId, partnerDiscordId);
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
     * @param memberId          the INVITED row's id, carried by the accept button
     * @param respondingDiscordId only this account's own invite can be answered with it
     */
    public AnswerResult accept(final UUID memberId, final String respondingDiscordId) {
        if (dao.accept(memberId, respondingDiscordId) != 1) {
            return AnswerResult.notPending();
        }
        final UUID teamId = dao.teamIdOfMember(memberId).orElseThrow();
        return AnswerResult.answered(teamId, dao.teamName(teamId).orElseThrow());
    }

    /** @see #accept(UUID, String) */
    public AnswerResult decline(final UUID memberId, final String respondingDiscordId) {
        if (dao.decline(memberId, respondingDiscordId) != 1) {
            return AnswerResult.notPending();
        }
        final UUID teamId = dao.teamIdOfMember(memberId).orElseThrow();
        return AnswerResult.answered(teamId, dao.teamName(teamId).orElseThrow());
    }

    /** @return the account's language, English when nothing is known about it yet */
    public Locale localeOf(final String discordId) {
        return Locales.parse(dao.localeOf(discordId).orElse(null));
    }

    /** @return the OWNER of a team, so an invite's accept/decline can be reported back to them */
    public Optional<String> ownerOf(final UUID teamId) {
        return dao.ownerDiscordId(teamId);
    }

    private static boolean isUniqueViolation(final UnableToExecuteStatementException exception) {
        return exception.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState());
    }
}
