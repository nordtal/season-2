-- A duel record names its kit alone: every running smp writes the kit, so the old duel type goes.
-- A row an older smp wrote after the kit column came gets its kit from its type first.
UPDATE smp_aura_duel SET kit = CASE duel_type WHEN 'SWORD' THEN 'Sword' ELSE 'Bow' END WHERE kit IS NULL;
ALTER TABLE smp_aura_duel DROP CONSTRAINT smp_aura_duel_kit_or_type_check;
ALTER TABLE smp_aura_duel ALTER COLUMN kit SET NOT NULL;
ALTER TABLE smp_aura_duel DROP COLUMN duel_type;
