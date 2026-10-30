-- A duel records the kit its two fighters agreed on, by the kit's name. The old types become the kits Sword and Bow.
-- The type column stays, nullable, until no running smp writes it.
ALTER TABLE smp_aura_duel ADD COLUMN kit varchar(64);
UPDATE smp_aura_duel SET kit = CASE duel_type WHEN 'SWORD' THEN 'Sword' ELSE 'Bow' END;
ALTER TABLE smp_aura_duel ALTER COLUMN duel_type DROP NOT NULL;
ALTER TABLE smp_aura_duel ADD CONSTRAINT smp_aura_duel_kit_or_type_check CHECK (kit IS NOT NULL OR duel_type IS NOT NULL);
