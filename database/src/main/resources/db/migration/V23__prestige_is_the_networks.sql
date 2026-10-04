-- Every server and the proxy draw a player's crest on the card their name carries, so the prestige group is the
-- network's: an admin's values move with it, and smp no longer publishes a group of that name.
UPDATE setting_override SET service = 'network' WHERE service = 'smp' AND name = 'prestige';
DELETE FROM setting_group WHERE service = 'smp' AND name = 'prestige';
