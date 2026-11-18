-- The spawn NPC is an NPC of the npc tables, so smp no longer publishes its npc group. Steward lists every row of
-- setting_group, so the row of the last release that published it would stay on the settings page.
DELETE FROM setting_override_node WHERE service = 'smp' AND name = 'npc';
DELETE FROM setting_override WHERE service = 'smp' AND name = 'npc';
DELETE FROM setting_group WHERE service = 'smp' AND name = 'npc';
