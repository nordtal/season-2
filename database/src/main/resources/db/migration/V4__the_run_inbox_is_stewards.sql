-- The run inbox takes the name of steward, which claims every row, as every other inbox is named after its consumer.
-- A rename keeps the rows, the grants and service_hold's foreign key; the names hanging off the table follow it.
ALTER TABLE worker_inbox RENAME TO steward_inbox;
ALTER SEQUENCE worker_inbox_id_seq RENAME TO steward_inbox_id_seq;
ALTER TABLE steward_inbox RENAME CONSTRAINT worker_inbox_pkey TO steward_inbox_pkey;
ALTER TABLE steward_inbox RENAME CONSTRAINT worker_inbox_kind_check TO steward_inbox_kind_check;
ALTER TABLE steward_inbox RENAME CONSTRAINT worker_inbox_services_check TO steward_inbox_services_check;
ALTER TABLE steward_inbox RENAME CONSTRAINT worker_inbox_status_check TO steward_inbox_status_check;
ALTER TABLE steward_inbox RENAME CONSTRAINT worker_inbox_actor_kind_check TO steward_inbox_actor_kind_check;
ALTER TABLE steward_inbox RENAME CONSTRAINT worker_inbox_actor_id_iff_person TO steward_inbox_actor_id_iff_person;
ALTER TABLE steward_inbox RENAME CONSTRAINT worker_inbox_finished_iff_settled TO steward_inbox_finished_iff_settled;
ALTER INDEX worker_inbox_pending RENAME TO steward_inbox_pending;
ALTER INDEX worker_inbox_one_open RENAME TO steward_inbox_one_open;
