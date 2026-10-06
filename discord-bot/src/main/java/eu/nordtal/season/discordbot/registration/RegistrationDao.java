package eu.nordtal.season.discordbot.registration;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Notifies;
import eu.nordtal.season.database.registration.Membership;
import eu.nordtal.season.database.registration.RegistrationState;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * The SQL over {@code registration}, {@code team} and {@code team_member}, every query named by its game's key.
 *
 * Unique indexes hold the invariants, {@link Teams} only pre-checks; every write signals {@link Channel#HUNGER_GAMES}.
 */
@Notifies(Channel.HUNGER_GAMES)
interface RegistrationDao {

    /** A round of registration that has not ended. */
    record Current(UUID id, RegistrationState state) {}

    /** The one round of {@code game} that has not ended, if any. */
    @SqlQuery("SELECT id, state FROM registration WHERE game = :game AND state <> 'ENDED'")
    @RegisterConstructorMapper(Current.class)
    Optional<Current> current(@Bind("game") String game);

    @SqlQuery("""
            WITH opened AS (INSERT INTO registration (game) VALUES (:game) RETURNING id, state)
            SELECT opened.*, pg_notify(:channel, '') AS notified FROM opened
            """)
    @RegisterConstructorMapper(Current.class)
    Current open(@Bind("game") String game);

    /**
     * Returns the round while it is open, and holds it so until the transaction ends.
     *
     * The game's close waits for the hold, so a team written under it is in the roster the game starts with.
     */
    @SqlQuery("SELECT id FROM registration WHERE id = :id AND state = 'OPEN' FOR SHARE")
    Optional<UUID> holdOpen(@Bind("id") UUID registrationId);

    @SqlQuery("""
            SELECT EXISTS (SELECT 1 FROM team WHERE registration_id = :registrationId AND lower(name) = lower(:name))
            """)
    boolean teamNameTaken(@Bind("registrationId") UUID registrationId, @Bind("name") String name);

    /** OWNER, INVITED or ACCEPTED; a DECLINED row does not count. */
    @SqlQuery("""
            SELECT id FROM team_member
            WHERE registration_id = :registrationId AND discord_id = :discordId
              AND state IN ('OWNER', 'INVITED', 'ACCEPTED')
            """)
    Optional<UUID> activeMembershipId(
            @Bind("registrationId") UUID registrationId, @Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            WITH inserted AS (INSERT INTO team (registration_id, name) VALUES (:registrationId, :name) RETURNING id)
            SELECT id FROM (SELECT inserted.id, pg_notify(:channel, '') FROM inserted) AS notified
            """)
    UUID insertTeam(@Bind("registrationId") UUID registrationId, @Bind("name") String name);

    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO team_member (team_id, registration_id, discord_id, state)
                VALUES (:teamId, :registrationId, :discordId, 'OWNER')
                RETURNING id
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM inserted) AS notified
            """)
    int insertOwner(
            @Bind("teamId") UUID teamId,
            @Bind("registrationId") UUID registrationId,
            @Bind("discordId") DiscordId discordId);

    @SqlQuery("SELECT team_id FROM team_member WHERE id = :memberId")
    Optional<UUID> teamIdOfMember(@Bind("memberId") UUID memberId);

    @SqlQuery("SELECT state FROM team_member WHERE id = :memberId")
    Optional<Membership> stateOfMember(@Bind("memberId") UUID memberId);

    @SqlQuery("SELECT name FROM team WHERE id = :teamId")
    Optional<String> teamName(@Bind("teamId") UUID teamId);

    @SqlQuery("SELECT discord_id FROM team_member WHERE team_id = :teamId AND state = 'OWNER'")
    Optional<String> ownerDiscordId(@Bind("teamId") UUID teamId);

    /** OWNER and ACCEPTED; a pending invite does not occupy the second seat yet. */
    @SqlQuery("SELECT COUNT(*) FROM team_member WHERE team_id = :teamId AND state IN ('OWNER', 'ACCEPTED')")
    int settledMemberCount(@Bind("teamId") UUID teamId);

    @SqlQuery("SELECT EXISTS (SELECT 1 FROM team_member WHERE team_id = :teamId AND state = 'INVITED')")
    boolean hasPendingInvite(@Bind("teamId") UUID teamId);

    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO team_member (team_id, registration_id, discord_id, state)
                VALUES (:teamId, :registrationId, :discordId, 'INVITED')
                RETURNING id
            )
            SELECT id FROM (SELECT inserted.id, pg_notify(:channel, '') FROM inserted) AS notified
            """)
    UUID insertInvite(
            @Bind("teamId") UUID teamId,
            @Bind("registrationId") UUID registrationId,
            @Bind("discordId") DiscordId discordId);

    /** The state of the round an invite belongs to, or nothing for an invite that does not exist. */
    @SqlQuery("""
            SELECT registration.state FROM team_member member
            JOIN registration ON registration.id = member.registration_id
            WHERE member.id = :memberId
            """)
    Optional<RegistrationState> registrationStateOf(@Bind("memberId") UUID memberId);

    // Only the invited account answers its own invite, and only while the round is open, held so as holdOpen holds it.
    @SqlQuery("""
            WITH accepted AS (
                UPDATE team_member member SET state = 'ACCEPTED'
                WHERE id = :memberId AND discord_id = :discordId AND state = 'INVITED'
                  AND member.registration_id IN (SELECT id FROM registration WHERE state = 'OPEN' FOR SHARE)
                RETURNING id
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM accepted) AS notified
            """)
    int accept(@Bind("memberId") UUID memberId, @Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            WITH declined AS (
                UPDATE team_member SET state = 'DECLINED'
                WHERE id = :memberId AND discord_id = :discordId AND state = 'INVITED'
                RETURNING id
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM declined) AS notified
            """)
    int decline(@Bind("memberId") UUID memberId, @Bind("discordId") DiscordId discordId);
}
