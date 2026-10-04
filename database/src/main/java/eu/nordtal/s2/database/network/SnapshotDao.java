package eu.nordtal.s2.database.network;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * The one query behind every MOTD placeholder and every status channel name.
 * Scalar subqueries off a one-row {@code VALUES}, over the round discord-bot registers and what the games write.
 */
interface SnapshotDao {

    @SqlQuery("""
            WITH round AS (SELECT id FROM registration WHERE game = :game AND state <> 'ENDED'),
                 -- A game under way, whose deaths count; an aborted one's do not, since its round plays again.
                 game AS (SELECT id FROM hg_game WHERE state IN ('COUNTDOWN', 'RUNNING'))
            SELECT (SELECT count(*) FROM team WHERE team.registration_id = (SELECT id FROM round)) AS hg_teams,

                   -- "On the team" is OWNER or ACCEPTED; an INVITED row is an unanswered question.
                   (SELECT count(*) FROM team_member member
                    WHERE member.registration_id = (SELECT id FROM round)
                      AND member.state IN ('OWNER', 'ACCEPTED'))                             AS hg_participants,

                   -- Counted over the same set hg_participants counts, so that eliminated can
                   -- never exceed it and the mapper's alive = participants - eliminated cannot go
                   -- negative or leave the two disagreeing in the server browser.
                   (SELECT count(DISTINCT event.victim_id) FROM hg_event event
                    WHERE event.game_id = (SELECT id FROM game)
                      AND event.type = 'DEATH'
                      AND EXISTS (SELECT 1 FROM team_member member
                                  WHERE member.id = event.victim_id
                                    AND member.registration_id = (SELECT id FROM round)
                                    AND member.state IN ('OWNER', 'ACCEPTED')))              AS hg_eliminated,

                   -- A team is still in while any of its members has no DEATH against them in this game.
                   (SELECT count(*) FROM team
                    WHERE team.registration_id = (SELECT id FROM round)
                      AND EXISTS (SELECT 1 FROM team_member member
                                  WHERE member.team_id = team.id
                                    AND member.state IN ('OWNER', 'ACCEPTED')
                                    AND NOT EXISTS (SELECT 1 FROM hg_event event
                                                    WHERE event.victim_id = member.id
                                                      AND event.game_id = (SELECT id FROM game)
                                                      AND event.type = 'DEATH')))            AS hg_teams_alive,

                   (SELECT milestone.key FROM smp_milestone milestone
                    WHERE milestone.state = 'ACTIVE' LIMIT 1)                                AS smp_milestone,

                   -- The active milestone's progress: collected against asked-for, across its
                   -- objectives, capped per objective so one overshooting hand-in cannot carry the
                   -- others. NULL when nothing is active, which the mapper reads as 0.
                   (SELECT floor(100 * sum(least(objective.amount, objective.target))
                                     / nullif(sum(objective.target), 0))
                    FROM smp_objective objective
                    WHERE objective.milestone_key = (SELECT milestone.key FROM smp_milestone milestone
                                                     WHERE milestone.state = 'ACTIVE' LIMIT 1))
                                                                                             AS smp_progress,

                   (SELECT count(*) FROM smp_milestone milestone
                    WHERE milestone.state = 'UNLOCKED')                                      AS smp_milestones_done,
                   (SELECT count(*) FROM smp_milestone)                                      AS smp_milestones,
                   (SELECT coalesce(sum(player.aura), 0) FROM smp_player player)             AS smp_aura_total,
                   (SELECT count(*) FROM smp_player)                                         AS smp_players
            FROM (VALUES (1)) AS anchor (one)
            """)
    @RegisterRowMapper(SnapshotMapper.class)
    NetworkSnapshot snapshot(@Bind("game") String game);
}
