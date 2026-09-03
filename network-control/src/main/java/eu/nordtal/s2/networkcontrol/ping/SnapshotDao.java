package eu.nordtal.s2.networkcontrol.ping;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * The one query behind every MOTD placeholder.
 *
 * <p><b>One query, not six.</b> It runs on a timer while nobody is necessarily looking, so its cost
 * is a standing cost of running the proxy - and six round trips on a ten-second interval is six
 * times a standing cost for numbers that decorate a server list. Every part of it is a count over a
 * table with an index on the column it filters by, and the whole thing is scalar subqueries hung
 * off a one-row {@code VALUES}, which is the same shape (and the same reasoning) as the login
 * query in {@code AccessDao}.
 *
 * <p><b>Read-only, and deliberately reading tables two other plugins own.</b> The alternative was
 * having {@code hunger-games} and {@code smp} push their state to the proxy over a plugin-message
 * channel, which is a second copy of a running game's state plus a stale-detection problem for
 * when a backend is down. Counting rows in the database those plugins already write is the cheaper
 * half of that trade by a wide margin - the cost is that this query knows their schema, which is
 * why it names {@code V5} and {@code V6} in its comments.
 *
 * <p>Alive is derived from {@code hg_event}, not from anything a plugin stores: an elimination
 * writes a {@code DEATH} row with the victim on it ({@code WinTracker}), and the in-memory alive
 * set that plugin keeps is not visible from here. Counting distinct victims is therefore the only
 * answer available without new plumbing, and it is exact for a game that is running.
 */
interface SnapshotDao {

    @SqlQuery("""
            SELECT (SELECT game.state FROM hg_game game WHERE game.state <> 'DECIDED')       AS hg_state,

                   (SELECT count(*) FROM hg_team team
                    WHERE team.game_id = (SELECT game.id FROM hg_game game
                                          WHERE game.state <> 'DECIDED'))                    AS hg_teams,

                   -- "On the team" is OWNER or ACCEPTED; an INVITED row is an unanswered question
                   -- and not a participant (V5__hunger_games.sql).
                   (SELECT count(*) FROM hg_member member
                    WHERE member.game_id = (SELECT game.id FROM hg_game game
                                            WHERE game.state <> 'DECIDED')
                      AND member.state IN ('OWNER', 'ACCEPTED'))                             AS hg_participants,

                   -- Counted over the same set hg_participants counts, so that eliminated can
                   -- never exceed it and the mapper's alive = participants - eliminated cannot go
                   -- negative or leave the two disagreeing in the server browser. The EXISTS also
                   -- subsumes the victim_id IS NOT NULL this used to carry: a DEATH row whose
                   -- member has been deleted has victim_id SET NULL (V5) and matches nothing.
                   (SELECT count(DISTINCT event.victim_id) FROM hg_event event
                    WHERE event.game_id = (SELECT game.id FROM hg_game game
                                           WHERE game.state <> 'DECIDED')
                      AND event.type = 'DEATH'
                      AND EXISTS (SELECT 1 FROM hg_member member
                                  WHERE member.id = event.victim_id
                                    AND member.state IN ('OWNER', 'ACCEPTED')))              AS hg_eliminated,

                   -- A team is still in while any of its members has no DEATH against them.
                   (SELECT count(*) FROM hg_team team
                    WHERE team.game_id = (SELECT game.id FROM hg_game game
                                          WHERE game.state <> 'DECIDED')
                      AND EXISTS (SELECT 1 FROM hg_member member
                                  WHERE member.team_id = team.id
                                    AND member.state IN ('OWNER', 'ACCEPTED')
                                    AND NOT EXISTS (SELECT 1 FROM hg_event event
                                                    WHERE event.victim_id = member.id
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
    NetworkSnapshot snapshot();

}
