-- The table comments name the services as they now are: steward, and steward-bunq for the bank key.
-- V1 is frozen, so they are issued again here; nothing else changes.

COMMENT ON TABLE discord_user IS 'Owned by discord-bot, which mirrors guild membership, locale and profile. steward writes the admin tree and the pack exemption columns.';
COMMENT ON TABLE admin_grant IS 'Owned by steward: every admin grant ever made, for the limit per hour that the tree itself cannot count once a grant is revoked.';
COMMENT ON TABLE payment_request IS 'Owned by discord-bot, which opens, cancels and settles a request and asks the bank''s inbox for its tab. steward writes the bank side: the tab, its failure, its cancellation and the match.';
COMMENT ON TABLE payment_notice IS 'Owned by steward: a bank payment it could not book, raised once. discord-bot posts it and sets posted.';
COMMENT ON TABLE payment_gateway IS 'Owned by steward: whether steward-bunq can take money, and the cut-off of the payment poll; one row. discord-bot reads the state.';
COMMENT ON TABLE season_phase IS 'Owned by steward, where an admin moves the season on; one row. discord-bot writes it too, from the season commands.';
COMMENT ON TABLE worker_inbox IS 'Owned by steward, which claims and runs every row. Its interface, its own schedule and the host installer ask for runs; the interface cancels them.';
COMMENT ON TABLE service_hold IS 'Owned by steward: a service deliberately stopped, which stays stopped until someone starts it. No row is the ordinary case.';
COMMENT ON TABLE service_plugin IS 'Owned by steward: a plugin an admin added. The plugins the network needs are in the topology and never here, which is what makes them unremovable.';
COMMENT ON TABLE metric_sample IS 'Owned by steward, which samples every 30 s, compacts to hours after 30 days and draws it.';
COMMENT ON TABLE bot_inbox IS 'Owned by discord-bot, which claims and carries out every row. steward asks for access changes, announcements and a reload of the messages, smp for its announcements.';
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row. steward asks for the track actions and for a reload.';
COMMENT ON TABLE hunger_games_inbox IS 'Owned by hunger-games, which claims and carries out every row. steward asks for the start and for a reload.';
COMMENT ON TABLE limbo_inbox IS 'Owned by limbo, which claims and carries out every row. steward asks for a reload.';
COMMENT ON TABLE proxy_inbox IS 'Owned by the proxy, which claims and carries out every row. steward asks for a reload.';
COMMENT ON TABLE bank_inbox IS 'Owned by steward, which claims every row and puts it to steward-bunq, the holder of the bank key. discord-bot asks for a payment''s tab and for its cancellation; steward asks for the cancellation of what expires.';
COMMENT ON TABLE audit_log IS 'Owned by steward, whose journal reads it. Every service that changes access, the phase or a setting appends to it and never updates it.';
COMMENT ON TABLE steward_session IS 'Owned by steward: one signed-in browser.';
COMMENT ON TABLE steward_credential IS 'Owned by steward: one registered security key. An account with none cannot use Steward.';
COMMENT ON TABLE steward_push_subscription IS 'Owned by steward: one browser''s Web Push subscription, deleted when the push service answers 404 or 410.';
COMMENT ON TABLE steward_push_preference IS 'Owned by steward: which alert types one account wants pushed.';
