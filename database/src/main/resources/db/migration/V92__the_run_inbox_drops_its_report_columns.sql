-- A run's request is its kind and its rows, its state is its stage and step rows, and run_service holds the services
-- it stops. No release that runs beside this one reads the payload, the outcome or the moving array.
ALTER TABLE steward_inbox
    DROP COLUMN outcome,
    DROP COLUMN payload,
    DROP COLUMN moving;
