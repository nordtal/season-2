-- Steward logs in as its own role from this release on, where it had the owner's until now.
--
-- V1 and V2 ran on the installations of release 0.10 with steward's role named nordtal_steward_ui, and every
-- later migration grants to nordtal_steward, so on those installations the role steward now logs in as holds
-- nothing V1 granted. The first half grants it again, by today's table names; on a new installation it changes
-- nothing. The second half is what steward's own loops write, which the owner did for it until now. Clearing old
-- requests out of every inbox stays steward-agent's, as the owner.

-- What V1 granted steward.
GRANT ${role_read} TO ${role_steward_ui};
GRANT SELECT, INSERT ON smp_inbox, hunger_games_inbox, bot_inbox TO ${role_steward_ui};
GRANT USAGE ON SEQUENCE smp_inbox_id_seq, hunger_games_inbox_id_seq, bot_inbox_id_seq TO ${role_steward_ui};
GRANT INSERT ON audit_log TO ${role_steward_ui};
GRANT UPDATE ON season_phase, access_grant TO ${role_steward_ui};
GRANT SELECT, INSERT, UPDATE, DELETE ON admin_grant, steward_session, steward_credential, steward_push_subscription,
    steward_alert_preference TO ${role_steward_ui};
GRANT USAGE ON SEQUENCE admin_grant_id_seq TO ${role_steward_ui};
GRANT INSERT, UPDATE ON discord_user TO ${role_steward_ui};
GRANT SELECT ON audit_log, payment_notice, metric_sample, service_hold, hg_game, hg_team, hg_member,
    smp_milestone, smp_objective TO ${role_steward_ui};
GRANT SELECT, INSERT, UPDATE ON steward_inbox TO ${role_steward_ui};
GRANT USAGE ON SEQUENCE steward_inbox_id_seq TO ${role_steward_ui};

-- The curves: a sample every 30 s, compacted to hours, the raw rows forgotten.
GRANT INSERT, DELETE ON metric_sample TO ${role_steward_ui};

-- The payment poll: whether bunq is configured and the cut-off, the requests it matches, expires and cancels,
-- the payments a human has to look at, and the bank's inbox it claims and asks.
GRANT SELECT, INSERT, UPDATE ON payment_gateway TO ${role_steward_ui};
GRANT UPDATE ON payment_request TO ${role_steward_ui};
GRANT INSERT ON payment_notice TO ${role_steward_ui};
GRANT SELECT, INSERT, UPDATE ON bank_inbox TO ${role_steward_ui};
GRANT USAGE ON SEQUENCE bank_inbox_id_seq TO ${role_steward_ui};

-- A reload of the messages reaches every server, the proxy and limbo included.
GRANT SELECT, INSERT ON limbo_inbox, proxy_inbox TO ${role_steward_ui};
GRANT USAGE ON SEQUENCE limbo_inbox_id_seq, proxy_inbox_id_seq TO ${role_steward_ui};
