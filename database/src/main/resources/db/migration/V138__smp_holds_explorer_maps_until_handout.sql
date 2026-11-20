ALTER TABLE smp_pending_item
    ADD COLUMN structure text NOT NULL DEFAULT '',
    ADD COLUMN objective_reward boolean NOT NULL DEFAULT false;
